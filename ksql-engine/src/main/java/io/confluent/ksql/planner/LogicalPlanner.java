/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.planner;

import io.confluent.ksql.analyzer.AggregateAnalysisResult;
import io.confluent.ksql.analyzer.Analysis.AliasedDataSource;
import io.confluent.ksql.analyzer.Analysis.Into;
import io.confluent.ksql.analyzer.Analysis.JoinInfo;
import io.confluent.ksql.analyzer.ImmutableAnalysis;
import io.confluent.ksql.analyzer.RewrittenAnalysis;
import io.confluent.ksql.analyzer.SourceSchemas;
import io.confluent.ksql.engine.rewrite.ExpressionTreeRewriter;
import io.confluent.ksql.engine.rewrite.ExpressionTreeRewriter.Context;
import io.confluent.ksql.execution.expression.tree.ColumnReferenceExp;
import io.confluent.ksql.execution.expression.tree.Expression;
import io.confluent.ksql.execution.expression.tree.FunctionCall;
import io.confluent.ksql.execution.expression.tree.QualifiedColumnReferenceExp;
import io.confluent.ksql.execution.expression.tree.UnqualifiedColumnReferenceExp;
import io.confluent.ksql.execution.expression.tree.VisitParentExpressionVisitor;
import io.confluent.ksql.execution.plan.SelectExpression;
import io.confluent.ksql.execution.streams.timestamp.TimestampExtractionPolicyFactory;
import io.confluent.ksql.execution.timestamp.TimestampColumn;
import io.confluent.ksql.execution.util.ExpressionTypeManager;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.metastore.model.KeyField;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.planner.plan.AggregateNode;
import io.confluent.ksql.planner.plan.DataSourceNode;
import io.confluent.ksql.planner.plan.FilterNode;
import io.confluent.ksql.planner.plan.FlatMapNode;
import io.confluent.ksql.planner.plan.JoinNode;
import io.confluent.ksql.planner.plan.KsqlBareOutputNode;
import io.confluent.ksql.planner.plan.KsqlStructuredDataOutputNode;
import io.confluent.ksql.planner.plan.OutputNode;
import io.confluent.ksql.planner.plan.PlanNode;
import io.confluent.ksql.planner.plan.PlanNodeId;
import io.confluent.ksql.planner.plan.ProjectNode;
import io.confluent.ksql.planner.plan.RepartitionNode;
import io.confluent.ksql.schema.ksql.Column;
import io.confluent.ksql.schema.ksql.Column.Namespace;
import io.confluent.ksql.schema.ksql.FormatOptions;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.LogicalSchema.Builder;
import io.confluent.ksql.schema.ksql.types.SqlType;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.SchemaUtil;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

// CHECKSTYLE_RULES.OFF: ClassDataAbstractionCoupling
public class LogicalPlanner {
  // CHECKSTYLE_RULES.ON: ClassDataAbstractionCoupling

  private final KsqlConfig ksqlConfig;
  private final RewrittenAnalysis analysis;
  private final AggregateAnalysisResult aggregateAnalysis;
  private final FunctionRegistry functionRegistry;

  public LogicalPlanner(
      final KsqlConfig ksqlConfig,
      final ImmutableAnalysis analysis,
      final AggregateAnalysisResult aggregateAnalysis,
      final FunctionRegistry functionRegistry
  ) {
    this.ksqlConfig = Objects.requireNonNull(ksqlConfig, "ksqlConfig");
    Objects.requireNonNull(analysis, "analysis");
    final ColumnReferenceRewriter refRewriter =
        new ColumnReferenceRewriter(analysis.getFromSourceSchemas(false));
    this.analysis = new RewrittenAnalysis(analysis, refRewriter::process);
    this.aggregateAnalysis = new RewrittenAggregateAnalysis(
        Objects.requireNonNull(aggregateAnalysis, "aggregateAnalysis"),
        refRewriter::process
    );
    this.functionRegistry = Objects.requireNonNull(functionRegistry, "functionRegistry");
  }

  public OutputNode buildPlan() {
    PlanNode currentNode = buildSourceNode();

    if (analysis.getWhereExpression().isPresent()) {
      currentNode = buildFilterNode(currentNode, analysis.getWhereExpression().get());
    }

    if (analysis.getPartitionBy().isPresent()) {
      currentNode = buildRepartitionNode(
          "PartitionBy", currentNode, analysis.getPartitionBy().get());
    }

    if (!analysis.getTableFunctions().isEmpty()) {
      currentNode = buildFlatMapNode(currentNode);
    }

    if (analysis.getGroupByExpressions().isEmpty()) {
      currentNode = buildProjectNode(currentNode, "Project", currentNode.getSelectExpressions());
    } else {
      currentNode = buildAggregateNode(currentNode);
    }

    return buildOutputNode(currentNode);
  }

  private OutputNode buildOutputNode(final PlanNode sourcePlanNode) {
    final LogicalSchema inputSchema = sourcePlanNode.getSchema();
    final Optional<TimestampColumn> timestampColumn = getTimestampColumn(inputSchema, analysis);

    if (!analysis.getInto().isPresent()) {
      return new KsqlBareOutputNode(
          new PlanNodeId("KSQL_STDOUT_NAME"),
          sourcePlanNode,
          inputSchema,
          analysis.getLimitClause(),
          timestampColumn
      );
    }

    final Into intoDataSource = analysis.getInto().get();

    return new KsqlStructuredDataOutputNode(
        new PlanNodeId(intoDataSource.getName().text()),
        sourcePlanNode,
        inputSchema,
        timestampColumn,
        sourcePlanNode.getKeyField(),
        intoDataSource.getKsqlTopic(),
        analysis.getLimitClause(),
        intoDataSource.isCreate(),
        analysis.getSerdeOptions(),
        intoDataSource.getName()
    );
  }

  private Optional<TimestampColumn> getTimestampColumn(
      final LogicalSchema inputSchema,
      final ImmutableAnalysis analysis
  ) {
    final Optional<ColumnName> timestampColumnName =
        analysis.getProperties().getTimestampColumnName();
    final Optional<TimestampColumn> timestampColumn = timestampColumnName.map(
        n -> new TimestampColumn(n, analysis.getProperties().getTimestampFormat())
    );
    TimestampExtractionPolicyFactory.validateTimestampColumn(
        ksqlConfig,
        inputSchema,
        timestampColumn
    );
    return timestampColumn;
  }

  private AggregateNode buildAggregateNode(final PlanNode sourcePlanNode) {
    final List<Expression> groupByExps = analysis.getGroupByExpressions();

    final LogicalSchema schema = buildAggregateSchema(sourcePlanNode, groupByExps);

    final Expression groupBy = groupByExps.size() == 1
        ? groupByExps.get(0)
        : null;

    final Optional<ColumnName> keyFieldName = getSelectAliasMatching(
        (expression, alias) ->
            expression.equals(groupBy)
                && !SchemaUtil.isSystemColumn(alias)
                && !schema.isKeyColumn(alias),
        sourcePlanNode.getSelectExpressions());

    return new AggregateNode(
        new PlanNodeId("Aggregate"),
        sourcePlanNode,
        schema,
        keyFieldName,
        groupByExps,
        analysis.getWindowExpression(),
        aggregateAnalysis.getAggregateFunctionArguments(),
        aggregateAnalysis.getAggregateFunctions(),
        aggregateAnalysis.getRequiredColumns(),
        aggregateAnalysis.getFinalSelectExpressions(),
        aggregateAnalysis.getHavingExpression().orElse(null)
    );
  }

  private ProjectNode buildProjectNode(
      final PlanNode sourcePlanNode,
      final String id,
      final List<SelectExpression> projection) {
    final ColumnName sourceKeyFieldName = sourcePlanNode
        .getKeyField()
        .ref()
        .orElse(null);

    final LogicalSchema schema = buildProjectionSchema(sourcePlanNode.getSchema(), projection);

    final Optional<ColumnName> keyFieldName = getSelectAliasMatching(
        (expression, alias) -> expression instanceof UnqualifiedColumnReferenceExp
            && ((UnqualifiedColumnReferenceExp) expression).getColumnName().equals(
                sourceKeyFieldName),
        projection
    );

    return new ProjectNode(
        new PlanNodeId(id),
        sourcePlanNode,
        projection,
        schema,
        keyFieldName
    );
  }

  private static FilterNode buildFilterNode(
      final PlanNode sourcePlanNode,
      final Expression filterExpression
  ) {
    return new FilterNode(new PlanNodeId("WhereFilter"), sourcePlanNode, filterExpression);
  }

  private RepartitionNode buildRepartitionNode(
      final String planId,
      final PlanNode sourceNode,
      final Expression partitionBy
  ) {
    final KeyField keyField;

    if (!(partitionBy instanceof UnqualifiedColumnReferenceExp)) {
      keyField = KeyField.none();
    } else {
      final ColumnName columnName = ((UnqualifiedColumnReferenceExp) partitionBy).getColumnName();
      final LogicalSchema sourceSchema = sourceNode.getSchema();

      final Column proposedKey = sourceSchema
          .findColumn(columnName)
          .orElseThrow(() -> new KsqlException("Invalid identifier for PARTITION BY clause: '"
              + columnName.toString(FormatOptions.noEscape()) + "' Only columns from the "
              + "source schema can be referenced in the PARTITION BY clause."));

      switch (proposedKey.namespace()) {
        case KEY:
          keyField = sourceNode.getKeyField();
          break;
        case VALUE:
          keyField = KeyField.of(columnName);
          break;
        default:
          keyField = KeyField.none();
          break;
      }
    }

    final LogicalSchema schema = buildRepartitionedSchema(sourceNode, partitionBy);

    return new RepartitionNode(
        new PlanNodeId(planId),
        sourceNode,
        schema,
        partitionBy,
        keyField
    );
  }

  private FlatMapNode buildFlatMapNode(final PlanNode sourcePlanNode) {
    return new FlatMapNode(new PlanNodeId("FlatMap"), sourcePlanNode, functionRegistry, analysis);
  }

  private PlanNode buildSourceForJoin(
      final AliasedDataSource source,
      final String side,
      final Expression joinExpression) {
    final DataSourceNode sourceNode = new DataSourceNode(
        new PlanNodeId("KafkaTopic_" + side),
        source.getDataSource(),
        source.getAlias(),
        analysis.getSelectExpressions()
    );
    // it is always safe to build the repartition node - this operation will be
    // a no-op if a repartition is not required. if the source is a table, and
    // a repartition is needed, then an exception will be thrown
    final VisitParentExpressionVisitor<Optional<Expression>, Context<Void>> rewriter =
        new VisitParentExpressionVisitor<Optional<Expression>, Context<Void>>(Optional.empty()) {
          @Override
          public Optional<Expression> visitQualifiedColumnReference(
              final QualifiedColumnReferenceExp node,
              final Context<Void> ctx
          ) {
            return Optional.of(new UnqualifiedColumnReferenceExp(node.getColumnName()));
          }
        };
    final PlanNode repartition = buildRepartitionNode(
        side + "SourceKeyed",
        sourceNode,
        // We need to repartition on the original join expression, and we need to drop
        // all qualifiers.
        ExpressionTreeRewriter.rewriteWith(rewriter::process, joinExpression)
    );
    return buildProjectNode(
        repartition,
        "PrependAlias" + side,
        selectWithPrependAlias(source.getAlias(), repartition.getSchema())
    );
  }

  private PlanNode buildSourceNode() {

    final List<AliasedDataSource> sources = analysis.getFromDataSources();

    final Optional<JoinInfo> joinInfo = analysis.getOriginal().getJoin();
    if (!joinInfo.isPresent()) {
      return buildNonJoinNode(sources);
    }

    if (sources.size() != 2) {
      throw new IllegalStateException("Expected 2 sources. Got " + sources.size());
    }

    final AliasedDataSource left = sources.get(0);
    final AliasedDataSource right = sources.get(1);

    final PlanNode leftSourceNode = buildSourceForJoin(
        left,
        "Left",
        joinInfo.get().getLeftJoinExpression()
    );

    final PlanNode rightSourceNode = buildSourceForJoin(
        right,
        "Right",
        joinInfo.get().getRightJoinExpression()
    );

    return new JoinNode(
        new PlanNodeId("Join"),
        analysis.getSelectExpressions(),
        joinInfo.get().getType(),
        leftSourceNode,
        rightSourceNode,
        joinInfo.get().getWithinExpression()
    );
  }

  private DataSourceNode buildNonJoinNode(final List<AliasedDataSource> sources) {
    if (sources.size() != 1) {
      throw new IllegalStateException("Expected only 1 source, got: " + sources.size());
    }

    final AliasedDataSource dataSource = analysis.getFromDataSources().get(0);
    return new DataSourceNode(
        new PlanNodeId("KsqlTopic"),
        dataSource.getDataSource(),
        dataSource.getAlias(),
        analysis.getSelectExpressions()
    );
  }

  private static Optional<ColumnName> getSelectAliasMatching(
      final BiFunction<Expression, ColumnName, Boolean> matcher,
      final List<SelectExpression> projection
  ) {
    for (int i = 0; i < projection.size(); i++) {
      final SelectExpression select = projection.get(i);

      if (matcher.apply(select.getExpression(), select.getAlias())) {
        return Optional.of(select.getAlias());
      }
    }

    return Optional.empty();
  }

  private LogicalSchema buildProjectionSchema(
      final LogicalSchema schema,
      final List<SelectExpression> projection
  ) {
    final ExpressionTypeManager expressionTypeManager = new ExpressionTypeManager(
        schema,
        functionRegistry
    );

    final Builder builder = LogicalSchema.builder()
        .withRowTime();

    builder.keyColumns(schema.key());

    for (int i = 0; i < projection.size(); i++) {
      final SelectExpression select = projection.get(i);

      final SqlType expressionType = expressionTypeManager
          .getExpressionSqlType(select.getExpression());

      builder.valueColumn(select.getAlias(), expressionType);
    }

    return builder.build();
  }

  private LogicalSchema buildAggregateSchema(
      final PlanNode sourcePlanNode,
      final List<Expression> groupByExps
  ) {
    final LogicalSchema sourceSchema = sourcePlanNode.getSchema();

    final ColumnName keyName;
    final SqlType keyType;
    if (groupByExps.size() != 1) {
      keyName = SchemaUtil.ROWKEY_NAME;
      keyType = SqlTypes.STRING;
    } else {
      final Expression expression = groupByExps.get(0);

      keyName = exactlyMatchesKeyColumns(expression, sourceSchema)
          ? ((ColumnReferenceExp) expression).getColumnName()
          : SchemaUtil.ROWKEY_NAME;

      final ExpressionTypeManager typeManager =
          new ExpressionTypeManager(sourceSchema, functionRegistry);

      keyType = typeManager.getExpressionSqlType(expression);
    }

    final LogicalSchema projectionSchema = buildProjectionSchema(
        sourceSchema
            .withMetaAndKeyColsInValue(analysis.getWindowExpression().isPresent()),
        sourcePlanNode.getSelectExpressions()
    );

    return LogicalSchema.builder()
        .withRowTime()
        .keyColumn(keyName, keyType)
        .valueColumns(projectionSchema.value())
        .build();
  }

  private LogicalSchema buildRepartitionedSchema(
      final PlanNode sourceNode,
      final Expression partitionBy
  ) {
    final LogicalSchema sourceSchema = sourceNode.getSchema();

    if (exactlyMatchesKeyColumns(partitionBy, sourceSchema)) {
      // No-op:
      return sourceSchema;
    }

    final ExpressionTypeManager typeManager =
        new ExpressionTypeManager(sourceSchema, functionRegistry);

    final SqlType keyType = typeManager.getExpressionSqlType(partitionBy);

    return LogicalSchema.builder()
        .withRowTime()
        .keyColumn(SchemaUtil.ROWKEY_NAME, keyType)
        .valueColumns(sourceSchema.value())
        .build();
  }

  private static boolean exactlyMatchesKeyColumns(
      final Expression expression,
      final LogicalSchema schema
  ) {
    if (schema.key().size() != 1) {
      // Currently only support single key column:
      return false;
    }

    if (!(expression instanceof ColumnReferenceExp)) {
      // Anything not a column ref can't be a match:
      return false;
    }

    final ColumnName columnName = ((ColumnReferenceExp) expression).getColumnName();

    final Namespace ns = schema
        .findColumn(columnName)
        .map(Column::namespace)
        .orElse(Namespace.VALUE);

    return ns == Namespace.KEY;
  }

  private static List<SelectExpression> selectWithPrependAlias(
      final SourceName alias,
      final LogicalSchema schema
  ) {
    return schema.value().stream()
        .map(c -> SelectExpression.of(
            ColumnName.generatedJoinColumnAlias(alias, c.name()),
            new UnqualifiedColumnReferenceExp(c.name()))
        ).collect(Collectors.toList());
  }

  private static final class ColumnReferenceRewriter
      extends VisitParentExpressionVisitor<Optional<Expression>, Context<Void>> {
    final SourceSchemas sourceSchemas;

    ColumnReferenceRewriter(final SourceSchemas sourceSchemas) {
      super(Optional.empty());
      this.sourceSchemas = Objects.requireNonNull(sourceSchemas, "sourceSchemas");
    }

    @Override
    public Optional<Expression> visitColumnReference(
        final UnqualifiedColumnReferenceExp node,
        final Context<Void> ctx
    ) {
      if (sourceSchemas.isJoin()) {
        final SourceName sourceName = sourceSchemas
            .sourcesWithField(Optional.empty(), node.getColumnName()).iterator().next();

        return Optional.of(new UnqualifiedColumnReferenceExp(
            ColumnName.generatedJoinColumnAlias(sourceName, node.getColumnName())
        ));
      }
      return Optional.empty();
    }

    @Override
    public Optional<Expression> visitQualifiedColumnReference(
        final QualifiedColumnReferenceExp node,
        final Context<Void> ctx
    ) {
      if (sourceSchemas.isJoin()) {
        return Optional.of(new UnqualifiedColumnReferenceExp(
            ColumnName.generatedJoinColumnAlias(node.getQualifier(), node.getColumnName())
        ));
      } else {
        return Optional.of(new UnqualifiedColumnReferenceExp(node.getColumnName()));
      }
    }
  }

  private static final class RewrittenAggregateAnalysis implements AggregateAnalysisResult {

    private final AggregateAnalysisResult original;
    private final BiFunction<Expression, Context<Void>, Optional<Expression>> rewriter;

    private RewrittenAggregateAnalysis(
        final AggregateAnalysisResult original,
        final BiFunction<Expression, Context<Void>, Optional<Expression>> rewriter
    ) {
      this.original = Objects.requireNonNull(original, "original");
      this.rewriter = Objects.requireNonNull(rewriter, "rewriter");
    }

    @Override
    public List<Expression> getAggregateFunctionArguments() {
      return rewriteList(original.getAggregateFunctionArguments());
    }

    @Override
    public List<ColumnReferenceExp> getRequiredColumns() {
      return rewriteList(original.getRequiredColumns());
    }

    @Override
    public List<FunctionCall> getAggregateFunctions() {
      return rewriteList(original.getAggregateFunctions());
    }

    @Override
    public List<Expression> getFinalSelectExpressions() {
      return original.getFinalSelectExpressions().stream()
          .map(this::rewriteFinalSelectExpression)
          .collect(Collectors.toList());
    }

    @Override
    public Optional<Expression> getHavingExpression() {
      return rewriteOptional(original.getHavingExpression());
    }

    private Expression rewriteFinalSelectExpression(final Expression expression) {
      if (expression instanceof UnqualifiedColumnReferenceExp
          && ((UnqualifiedColumnReferenceExp) expression).getColumnName().isAggregate()) {
        return expression;
      }
      return ExpressionTreeRewriter.rewriteWith(rewriter, expression);
    }

    private <T extends Expression> Optional<T> rewriteOptional(final Optional<T> expression) {
      return expression.map(e -> ExpressionTreeRewriter.rewriteWith(rewriter, e));
    }

    private <T extends Expression> List<T> rewriteList(final List<T> expressions) {
      return expressions.stream()
          .map(e -> ExpressionTreeRewriter.rewriteWith(rewriter, e))
          .collect(Collectors.toList());
    }
  }
}
