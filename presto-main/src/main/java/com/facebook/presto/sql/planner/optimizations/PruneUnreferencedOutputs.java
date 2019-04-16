/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.optimizations;

import com.facebook.presto.Session;
import com.facebook.presto.execution.warnings.WarningCollector;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.PartitioningScheme;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.SymbolAllocator;
import com.facebook.presto.sql.planner.SymbolsExtractor;
import com.facebook.presto.sql.planner.TypeProvider;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.AggregationNode.Aggregation;
import com.facebook.presto.sql.planner.plan.ApplyNode;
import com.facebook.presto.sql.planner.plan.AssignUniqueId;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.DeleteNode;
import com.facebook.presto.sql.planner.plan.DistinctLimitNode;
import com.facebook.presto.sql.planner.plan.ExceptNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.ExplainAnalyzeNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.GroupIdNode;
import com.facebook.presto.sql.planner.plan.IndexJoinNode;
import com.facebook.presto.sql.planner.plan.IndexSourceNode;
import com.facebook.presto.sql.planner.plan.IntersectNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LateralJoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.MarkDistinctNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.RowNumberNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.SetOperationNode;
import com.facebook.presto.sql.planner.plan.SimplePlanRewriter;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.SpatialJoinNode;
import com.facebook.presto.sql.planner.plan.StatisticAggregations;
import com.facebook.presto.sql.planner.plan.StatisticsWriterNode;
import com.facebook.presto.sql.planner.plan.TableFinishNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.planner.plan.TopNRowNumberNode;
import com.facebook.presto.sql.planner.plan.UnionNode;
import com.facebook.presto.sql.planner.plan.UnnestNode;
import com.facebook.presto.sql.planner.plan.ValuesNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.Expression;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.facebook.presto.sql.planner.optimizations.AggregationNodeUtils.extractUnique;
import static com.facebook.presto.sql.planner.optimizations.QueryCardinalityUtil.isScalar;
import static com.facebook.presto.sql.relational.OriginalExpressionUtils.castToExpression;
import static com.facebook.presto.sql.relational.OriginalExpressionUtils.isExpression;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Sets.intersection;
import static java.util.Objects.requireNonNull;

/**
 * Removes all computation that does is not referenced transitively from the root of the plan
 * <p>
 * E.g.,
 * <p>
 * {@code Output[$0] -> Project[$0 := $1 + $2, $3 = $4 / $5] -> ...}
 * <p>
 * gets rewritten as
 * <p>
 * {@code Output[$0] -> Project[$0 := $1 + $2] -> ...}
 */
public class PruneUnreferencedOutputs
        implements PlanOptimizer
{
    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(session, "session is null");
        requireNonNull(types, "types is null");
        requireNonNull(symbolAllocator, "symbolAllocator is null");
        requireNonNull(idAllocator, "idAllocator is null");

        return SimplePlanRewriter.rewriteWith(new Rewriter(), plan, ImmutableSet.of());
    }

    private static class Rewriter
            extends SimplePlanRewriter<Set<Symbol>>
    {
        @Override
        public PlanNode visitExplainAnalyze(ExplainAnalyzeNode node, RewriteContext<Set<Symbol>> context)
        {
            return context.defaultRewrite(node, ImmutableSet.copyOf(node.getSource().getOutputSymbols()));
        }

        @Override
        public PlanNode visitExchange(ExchangeNode node, RewriteContext<Set<Symbol>> context)
        {
            Set<Symbol> expectedOutputSymbols = Sets.newHashSet(context.get());
            node.getPartitioningScheme().getHashColumn().ifPresent(variable -> expectedOutputSymbols.add(new Symbol(variable.getName())));
            node.getPartitioningScheme().getPartitioning().getColumns().stream()
                    .forEach(expectedOutputSymbols::add);
            node.getOrderingScheme().ifPresent(orderingScheme -> expectedOutputSymbols.addAll(orderingScheme.getOrderBy()));

            List<List<Symbol>> inputsBySource = new ArrayList<>(node.getInputs().size());
            for (int i = 0; i < node.getInputs().size(); i++) {
                inputsBySource.add(new ArrayList<>());
            }

            List<Symbol> newOutputSymbols = new ArrayList<>(node.getOutputSymbols().size());
            for (int i = 0; i < node.getOutputSymbols().size(); i++) {
                Symbol outputSymbol = node.getOutputSymbols().get(i);
                if (expectedOutputSymbols.contains(outputSymbol)) {
                    newOutputSymbols.add(outputSymbol);
                    for (int source = 0; source < node.getInputs().size(); source++) {
                        inputsBySource.get(source).add(node.getInputs().get(source).get(i));
                    }
                }
            }

            // newOutputSymbols contains all partition, sort and hash symbols so simply swap the output layout
            PartitioningScheme partitioningScheme = new PartitioningScheme(
                    node.getPartitioningScheme().getPartitioning(),
                    newOutputSymbols,
                    node.getPartitioningScheme().getHashColumn(),
                    node.getPartitioningScheme().isReplicateNullsAndAny(),
                    node.getPartitioningScheme().getBucketToPartition());

            ImmutableList.Builder<PlanNode> rewrittenSources = ImmutableList.builder();
            for (int i = 0; i < node.getSources().size(); i++) {
                ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                        .addAll(inputsBySource.get(i));

                rewrittenSources.add(context.rewrite(
                        node.getSources().get(i),
                        expectedInputs.build()));
            }

            return new ExchangeNode(
                    node.getId(),
                    node.getType(),
                    node.getScope(),
                    partitioningScheme,
                    rewrittenSources.build(),
                    inputsBySource,
                    node.getOrderingScheme());
        }

        @Override
        public PlanNode visitJoin(JoinNode node, RewriteContext<Set<Symbol>> context)
        {
            Set<Symbol> expectedFilterInputs = new HashSet<>();
            if (node.getFilter().isPresent()) {
                expectedFilterInputs = ImmutableSet.<Symbol>builder()
                        .addAll(SymbolsExtractor.extractUnique(castToExpression(node.getFilter().get())))
                        .addAll(context.get())
                        .build();
            }

            ImmutableSet.Builder<Symbol> leftInputsBuilder = ImmutableSet.builder();
            leftInputsBuilder.addAll(context.get()).addAll(Iterables.transform(node.getCriteria(), JoinNode.EquiJoinClause::getLeft));
            if (node.getLeftHashVariable().isPresent()) {
                leftInputsBuilder.add(new Symbol(node.getLeftHashVariable().get().getName()));
            }
            leftInputsBuilder.addAll(expectedFilterInputs);
            Set<Symbol> leftInputs = leftInputsBuilder.build();

            ImmutableSet.Builder<Symbol> rightInputsBuilder = ImmutableSet.builder();
            rightInputsBuilder.addAll(context.get()).addAll(Iterables.transform(node.getCriteria(), JoinNode.EquiJoinClause::getRight));
            if (node.getRightHashVariable().isPresent()) {
                rightInputsBuilder.add(new Symbol(node.getRightHashVariable().get().getName()));
            }
            rightInputsBuilder.addAll(expectedFilterInputs);
            Set<Symbol> rightInputs = rightInputsBuilder.build();

            PlanNode left = context.rewrite(node.getLeft(), leftInputs);
            PlanNode right = context.rewrite(node.getRight(), rightInputs);

            List<Symbol> outputSymbols;
            if (node.isCrossJoin()) {
                // do not prune nested joins output since it is not supported
                // TODO: remove this "if" branch when output symbols selection is supported by nested loop join
                outputSymbols = ImmutableList.<Symbol>builder()
                        .addAll(left.getOutputSymbols())
                        .addAll(right.getOutputSymbols())
                        .build();
            }
            else {
                outputSymbols = node.getOutputSymbols().stream()
                        .filter(context.get()::contains)
                        .distinct()
                        .collect(toImmutableList());
            }

            return new JoinNode(node.getId(), node.getType(), left, right, node.getCriteria(), outputSymbols, node.getFilter(), node.getLeftHashVariable(), node.getRightHashVariable(), node.getDistributionType());
        }

        @Override
        public PlanNode visitSemiJoin(SemiJoinNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> sourceInputsBuilder = ImmutableSet.builder();
            sourceInputsBuilder.addAll(context.get()).add(node.getSourceJoinSymbol());
            if (node.getSourceHashVariable().isPresent()) {
                sourceInputsBuilder.add(new Symbol(node.getSourceHashVariable().get().getName()));
            }
            Set<Symbol> sourceInputs = sourceInputsBuilder.build();

            ImmutableSet.Builder<Symbol> filteringSourceInputBuilder = ImmutableSet.builder();
            filteringSourceInputBuilder.add(node.getFilteringSourceJoinSymbol());
            if (node.getFilteringSourceHashVariable().isPresent()) {
                filteringSourceInputBuilder.add(new Symbol(node.getFilteringSourceHashVariable().get().getName()));
            }
            Set<Symbol> filteringSourceInputs = filteringSourceInputBuilder.build();

            PlanNode source = context.rewrite(node.getSource(), sourceInputs);
            PlanNode filteringSource = context.rewrite(node.getFilteringSource(), filteringSourceInputs);

            return new SemiJoinNode(node.getId(),
                    source,
                    filteringSource,
                    node.getSourceJoinSymbol(),
                    node.getFilteringSourceJoinSymbol(),
                    node.getSemiJoinOutput(),
                    node.getSourceHashVariable(),
                    node.getFilteringSourceHashVariable(),
                    node.getDistributionType());
        }

        @Override
        public PlanNode visitSpatialJoin(SpatialJoinNode node, RewriteContext<Set<Symbol>> context)
        {
            Set<Symbol> filterSymbols;
            if (isExpression(node.getFilter())) {
                filterSymbols = SymbolsExtractor.extractUnique(castToExpression(node.getFilter()));
            }
            else {
                filterSymbols = SymbolsExtractor.extractUnique(node.getFilter());
            }
            Set<Symbol> requiredInputs = ImmutableSet.<Symbol>builder()
                    .addAll(filterSymbols)
                    .addAll(context.get())
                    .build();

            ImmutableSet.Builder<Symbol> leftInputs = ImmutableSet.builder();
            node.getLeftPartitionSymbol().map(leftInputs::add);

            ImmutableSet.Builder<Symbol> rightInputs = ImmutableSet.builder();
            node.getRightPartitionSymbol().map(rightInputs::add);

            PlanNode left = context.rewrite(node.getLeft(), leftInputs.addAll(requiredInputs).build());
            PlanNode right = context.rewrite(node.getRight(), rightInputs.addAll(requiredInputs).build());

            List<Symbol> outputSymbols = node.getOutputSymbols().stream()
                    .filter(context.get()::contains)
                    .distinct()
                    .collect(toImmutableList());

            return new SpatialJoinNode(node.getId(), node.getType(), left, right, outputSymbols, node.getFilter(), node.getLeftPartitionSymbol(), node.getRightPartitionSymbol(), node.getKdbTree());
        }

        @Override
        public PlanNode visitIndexJoin(IndexJoinNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> probeInputsBuilder = ImmutableSet.builder();
            probeInputsBuilder.addAll(context.get())
                    .addAll(Iterables.transform(node.getCriteria(), IndexJoinNode.EquiJoinClause::getProbe));
            if (node.getProbeHashVariable().isPresent()) {
                probeInputsBuilder.add(new Symbol(node.getProbeHashVariable().get().getName()));
            }
            Set<Symbol> probeInputs = probeInputsBuilder.build();

            ImmutableSet.Builder<Symbol> indexInputBuilder = ImmutableSet.builder();
            indexInputBuilder.addAll(context.get())
                    .addAll(Iterables.transform(node.getCriteria(), IndexJoinNode.EquiJoinClause::getIndex));
            if (node.getIndexHashVariable().isPresent()) {
                indexInputBuilder.add(new Symbol(node.getIndexHashVariable().get().getName()));
            }
            Set<Symbol> indexInputs = indexInputBuilder.build();

            PlanNode probeSource = context.rewrite(node.getProbeSource(), probeInputs);
            PlanNode indexSource = context.rewrite(node.getIndexSource(), indexInputs);

            return new IndexJoinNode(node.getId(), node.getType(), probeSource, indexSource, node.getCriteria(), node.getProbeHashVariable(), node.getIndexHashVariable());
        }

        @Override
        public PlanNode visitIndexSource(IndexSourceNode node, RewriteContext<Set<Symbol>> context)
        {
            List<Symbol> newOutputSymbols = node.getOutputSymbols().stream()
                    .filter(context.get()::contains)
                    .collect(toImmutableList());

            Set<Symbol> newLookupSymbols = node.getLookupSymbols().stream()
                    .filter(context.get()::contains)
                    .collect(toImmutableSet());

            Map<Symbol, ColumnHandle> newAssignments = newOutputSymbols.stream()
                    .collect(Collectors.toMap(Function.identity(), node.getAssignments()::get));

            return new IndexSourceNode(node.getId(), node.getIndexHandle(), node.getTableHandle(), newLookupSymbols, newOutputSymbols, newAssignments, node.getCurrentConstraint());
        }

        @Override
        public PlanNode visitAggregation(AggregationNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                    .addAll(node.getGroupingKeys());
            if (node.getHashVariable().isPresent()) {
                expectedInputs.add(new Symbol(node.getHashVariable().get().getName()));
            }

            ImmutableMap.Builder<Symbol, Aggregation> aggregations = ImmutableMap.builder();
            for (Map.Entry<Symbol, Aggregation> entry : node.getAggregations().entrySet()) {
                Symbol symbol = entry.getKey();

                if (context.get().contains(symbol)) {
                    Aggregation aggregation = entry.getValue();
                    expectedInputs.addAll(extractUnique(aggregation));
                    aggregation.getMask().ifPresent(expectedInputs::add);
                    aggregations.put(symbol, aggregation);
                }
            }

            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());

            return new AggregationNode(node.getId(),
                    source,
                    aggregations.build(),
                    node.getGroupingSets(),
                    ImmutableList.of(),
                    node.getStep(),
                    node.getHashVariable(),
                    node.getGroupIdSymbol());
        }

        @Override
        public PlanNode visitWindow(WindowNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                    .addAll(context.get())
                    .addAll(node.getPartitionBy());

            node.getOrderingScheme().ifPresent(orderingScheme ->
                    orderingScheme.getOrderBy()
                            .forEach(expectedInputs::add));

            for (WindowNode.Frame frame : node.getFrames()) {
                if (frame.getStartValue().isPresent()) {
                    expectedInputs.add(frame.getStartValue().get());
                }
                if (frame.getEndValue().isPresent()) {
                    expectedInputs.add(frame.getEndValue().get());
                }
            }

            if (node.getHashVariable().isPresent()) {
                expectedInputs.add(new Symbol(node.getHashVariable().get().getName()));
            }

            ImmutableMap.Builder<VariableReferenceExpression, WindowNode.Function> functionsBuilder = ImmutableMap.builder();
            for (Map.Entry<VariableReferenceExpression, WindowNode.Function> entry : node.getWindowFunctions().entrySet()) {
                VariableReferenceExpression variable = entry.getKey();
                WindowNode.Function function = entry.getValue();
                if (context.get().contains(new Symbol(variable.getName()))) {
                    expectedInputs.addAll(WindowNodeUtil.extractWindowFunctionUnique(function));
                    functionsBuilder.put(variable, entry.getValue());
                }
            }

            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());

            Map<VariableReferenceExpression, WindowNode.Function> functions = functionsBuilder.build();

            if (functions.size() == 0) {
                return source;
            }

            return new WindowNode(
                    node.getId(),
                    source,
                    node.getSpecification(),
                    functions,
                    node.getHashVariable(),
                    node.getPrePartitionedInputs(),
                    node.getPreSortedOrderPrefix());
        }

        @Override
        public PlanNode visitTableScan(TableScanNode node, RewriteContext<Set<Symbol>> context)
        {
            List<Symbol> newOutputs = node.getOutputSymbols().stream()
                    .filter(context.get()::contains)
                    .collect(toImmutableList());
            Set<String> contextSymbols = context.get().stream()
                    .map(Symbol::getName)
                    .collect(toImmutableSet());
            List<VariableReferenceExpression> newVariables = node.getOutputVariables().stream()
                    .filter(variable -> contextSymbols.contains(variable.getName()))
                    .collect(toImmutableList());

            Map<Symbol, ColumnHandle> newAssignments = newOutputs.stream()
                    .collect(Collectors.toMap(Function.identity(), node.getAssignments()::get));

            return new TableScanNode(
                    node.getId(),
                    node.getTable(),
                    newOutputs,
                    newVariables,
                    newAssignments,
                    node.getCurrentConstraint(),
                    node.getEnforcedConstraint());
        }

        @Override
        public PlanNode visitFilter(FilterNode node, RewriteContext<Set<Symbol>> context)
        {
            Set<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                    .addAll(SymbolsExtractor.extractUnique(castToExpression(node.getPredicate())))
                    .addAll(context.get())
                    .build();

            PlanNode source = context.rewrite(node.getSource(), expectedInputs);

            return new FilterNode(node.getId(), source, node.getPredicate());
        }

        @Override
        public PlanNode visitGroupId(GroupIdNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.builder();

            List<Symbol> newAggregationArguments = node.getAggregationArguments().stream()
                    .filter(context.get()::contains)
                    .collect(Collectors.toList());
            expectedInputs.addAll(newAggregationArguments);

            ImmutableList.Builder<List<Symbol>> newGroupingSets = ImmutableList.builder();
            Map<Symbol, Symbol> newGroupingMapping = new HashMap<>();

            for (List<Symbol> groupingSet : node.getGroupingSets()) {
                ImmutableList.Builder<Symbol> newGroupingSet = ImmutableList.builder();

                for (Symbol output : groupingSet) {
                    if (context.get().contains(output)) {
                        newGroupingSet.add(output);
                        newGroupingMapping.putIfAbsent(output, node.getGroupingColumns().get(output));
                        expectedInputs.add(node.getGroupingColumns().get(output));
                    }
                }
                newGroupingSets.add(newGroupingSet.build());
            }

            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());
            return new GroupIdNode(node.getId(), source, newGroupingSets.build(), newGroupingMapping, newAggregationArguments, node.getGroupIdSymbol());
        }

        @Override
        public PlanNode visitMarkDistinct(MarkDistinctNode node, RewriteContext<Set<Symbol>> context)
        {
            if (!context.get().contains(node.getMarkerSymbol())) {
                return context.rewrite(node.getSource(), context.get());
            }

            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                    .addAll(node.getDistinctSymbols())
                    .addAll(context.get().stream()
                            .filter(symbol -> !symbol.equals(node.getMarkerSymbol()))
                            .collect(toImmutableList()));

            if (node.getHashVariable().isPresent()) {
                expectedInputs.add(new Symbol(node.getHashVariable().get().getName()));
            }
            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());

            return new MarkDistinctNode(node.getId(), source, node.getMarkerVariable(), node.getDistinctSymbols(), node.getHashVariable());
        }

        @Override
        public PlanNode visitUnnest(UnnestNode node, RewriteContext<Set<Symbol>> context)
        {
            List<Symbol> replicateSymbols = node.getReplicateSymbols().stream()
                    .filter(context.get()::contains)
                    .collect(toImmutableList());

            Optional<Symbol> ordinalitySymbol = node.getOrdinalitySymbol();
            if (ordinalitySymbol.isPresent() && !context.get().contains(ordinalitySymbol.get())) {
                ordinalitySymbol = Optional.empty();
            }
            Map<Symbol, List<Symbol>> unnestSymbols = node.getUnnestSymbols();
            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                    .addAll(replicateSymbols)
                    .addAll(unnestSymbols.keySet());

            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());
            return new UnnestNode(node.getId(), source, replicateSymbols, unnestSymbols, ordinalitySymbol);
        }

        @Override
        public PlanNode visitProject(ProjectNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.builder();

            Assignments.Builder builder = Assignments.builder();
            node.getAssignments().forEach((symbol, expression) -> {
                if (context.get().contains(symbol)) {
                    expectedInputs.addAll(SymbolsExtractor.extractUnique(expression));
                    builder.put(symbol, expression);
                }
            });

            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());

            return new ProjectNode(node.getId(), source, builder.build());
        }

        @Override
        public PlanNode visitOutput(OutputNode node, RewriteContext<Set<Symbol>> context)
        {
            Set<Symbol> expectedInputs = ImmutableSet.copyOf(node.getOutputSymbols());
            PlanNode source = context.rewrite(node.getSource(), expectedInputs);
            return new OutputNode(node.getId(), source, node.getColumnNames(), node.getOutputSymbols(), node.getOutputVariables());
        }

        @Override
        public PlanNode visitLimit(LimitNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                    .addAll(context.get());
            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());
            return new LimitNode(node.getId(), source, node.getCount(), node.isPartial());
        }

        @Override
        public PlanNode visitDistinctLimit(DistinctLimitNode node, RewriteContext<Set<Symbol>> context)
        {
            Set<Symbol> expectedInputs;
            if (node.getHashVariable().isPresent()) {
                expectedInputs = ImmutableSet.copyOf(concat(node.getDistinctSymbols(), ImmutableList.of(new Symbol(node.getHashVariable().get().getName()))));
            }
            else {
                expectedInputs = ImmutableSet.copyOf(node.getDistinctSymbols());
            }
            PlanNode source = context.rewrite(node.getSource(), expectedInputs);
            return new DistinctLimitNode(node.getId(), source, node.getLimit(), node.isPartial(), node.getDistinctSymbols(), node.getHashVariable());
        }

        @Override
        public PlanNode visitTopN(TopNNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                    .addAll(context.get())
                    .addAll(node.getOrderingScheme().getOrderBy());

            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());

            return new TopNNode(node.getId(), source, node.getCount(), node.getOrderingScheme(), node.getStep());
        }

        @Override
        public PlanNode visitRowNumber(RowNumberNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> inputsBuilder = ImmutableSet.builder();
            ImmutableSet.Builder<Symbol> expectedInputs = inputsBuilder
                    .addAll(context.get())
                    .addAll(node.getPartitionBy());

            if (node.getHashVariable().isPresent()) {
                inputsBuilder.add(new Symbol(node.getHashVariable().get().getName()));
            }
            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());

            return new RowNumberNode(node.getId(), source, node.getPartitionBy(), node.getRowNumberVariable(), node.getMaxRowCountPerPartition(), node.getHashVariable());
        }

        @Override
        public PlanNode visitTopNRowNumber(TopNRowNumberNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                    .addAll(context.get())
                    .addAll(node.getPartitionBy())
                    .addAll(node.getOrderingScheme().getOrderBy());

            if (node.getHashVariable().isPresent()) {
                expectedInputs.add(new Symbol(node.getHashVariable().get().getName()));
            }
            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());

            return new TopNRowNumberNode(node.getId(),
                    source,
                    node.getSpecification(),
                    node.getRowNumberVariable(),
                    node.getMaxRowCountPerPartition(),
                    node.isPartial(),
                    node.getHashVariable());
        }

        @Override
        public PlanNode visitSort(SortNode node, RewriteContext<Set<Symbol>> context)
        {
            Set<Symbol> expectedInputs = ImmutableSet.copyOf(concat(context.get(), node.getOrderingScheme().getOrderBy()));

            PlanNode source = context.rewrite(node.getSource(), expectedInputs);

            return new SortNode(node.getId(), source, node.getOrderingScheme());
        }

        @Override
        public PlanNode visitTableWriter(TableWriterNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableSet.Builder<Symbol> expectedInputs = ImmutableSet.<Symbol>builder()
                    .addAll(node.getColumns());
            if (node.getPartitioningScheme().isPresent()) {
                PartitioningScheme partitioningScheme = node.getPartitioningScheme().get();
                partitioningScheme.getPartitioning().getColumns().forEach(expectedInputs::add);
                partitioningScheme.getHashColumn().ifPresent(variable -> expectedInputs.add(new Symbol(variable.getName())));
            }
            if (node.getStatisticsAggregation().isPresent()) {
                StatisticAggregations aggregations = node.getStatisticsAggregation().get();
                expectedInputs.addAll(aggregations.getGroupingSymbols());
                aggregations.getAggregations().values().forEach(aggregation -> expectedInputs.addAll(extractUnique(aggregation)));
            }
            PlanNode source = context.rewrite(node.getSource(), expectedInputs.build());
            return new TableWriterNode(
                    node.getId(),
                    source,
                    node.getTarget(),
                    node.getRowCountSymbol(),
                    node.getFragmentSymbol(),
                    node.getTableCommitContextSymbol(),
                    node.getColumns(),
                    node.getColumnNames(),
                    node.getPartitioningScheme(),
                    node.getStatisticsAggregation(),
                    node.getStatisticsAggregationDescriptor());
        }

        @Override
        public PlanNode visitStatisticsWriterNode(StatisticsWriterNode node, RewriteContext<Set<Symbol>> context)
        {
            PlanNode source = context.rewrite(node.getSource(), ImmutableSet.copyOf(node.getSource().getOutputSymbols()));
            return new StatisticsWriterNode(
                    node.getId(),
                    source,
                    node.getTarget(),
                    node.getRowCountVariable(),
                    node.isRowCountEnabled(),
                    node.getDescriptor());
        }

        @Override
        public PlanNode visitTableFinish(TableFinishNode node, RewriteContext<Set<Symbol>> context)
        {
            PlanNode source = context.rewrite(node.getSource(), ImmutableSet.copyOf(node.getSource().getOutputSymbols()));
            return new TableFinishNode(
                    node.getId(),
                    source,
                    node.getTarget(),
                    node.getRowCountVariable(),
                    node.getStatisticsAggregation(),
                    node.getStatisticsAggregationDescriptor());
        }

        @Override
        public PlanNode visitDelete(DeleteNode node, RewriteContext<Set<Symbol>> context)
        {
            PlanNode source = context.rewrite(node.getSource(), ImmutableSet.of(node.getRowIdAsSymbol()));
            return new DeleteNode(node.getId(), source, node.getTarget(), node.getRowId(), node.getOutputSymbols(), node.getOutputVariables());
        }

        @Override
        public PlanNode visitUnion(UnionNode node, RewriteContext<Set<Symbol>> context)
        {
            ListMultimap<VariableReferenceExpression, VariableReferenceExpression> rewrittenVariableMapping = rewriteSetOperationVariableMapping(node, context);
            ImmutableList<PlanNode> rewrittenSubPlans = rewriteSetOperationSubPlans(node, context, rewrittenVariableMapping);
            return new UnionNode(node.getId(), rewrittenSubPlans, rewrittenVariableMapping);
        }

        @Override
        public PlanNode visitIntersect(IntersectNode node, RewriteContext<Set<Symbol>> context)
        {
            ListMultimap<VariableReferenceExpression, VariableReferenceExpression> rewrittenVariableMapping = rewriteSetOperationVariableMapping(node, context);
            ImmutableList<PlanNode> rewrittenSubPlans = rewriteSetOperationSubPlans(node, context, rewrittenVariableMapping);
            return new IntersectNode(node.getId(), rewrittenSubPlans, rewrittenVariableMapping);
        }

        @Override
        public PlanNode visitExcept(ExceptNode node, RewriteContext<Set<Symbol>> context)
        {
            ListMultimap<VariableReferenceExpression, VariableReferenceExpression> rewrittenVariableMapping = rewriteSetOperationVariableMapping(node, context);
            ImmutableList<PlanNode> rewrittenSubPlans = rewriteSetOperationSubPlans(node, context, rewrittenVariableMapping);
            return new ExceptNode(node.getId(), rewrittenSubPlans, rewrittenVariableMapping);
        }

        private ListMultimap<VariableReferenceExpression, VariableReferenceExpression> rewriteSetOperationVariableMapping(SetOperationNode node, RewriteContext<Set<Symbol>> context)
        {
            // Find out which output variables we need to keep
            Set<String> contextSymbolName = context.get().stream().map(Symbol::getName).collect(toImmutableSet());
            ImmutableListMultimap.Builder<VariableReferenceExpression, VariableReferenceExpression> rewrittenVariableMappingBuilder = ImmutableListMultimap.builder();
            for (VariableReferenceExpression variable : node.getOutputVariables()) {
                if (contextSymbolName.contains(variable.getName())) {
                    rewrittenVariableMappingBuilder.putAll(
                            variable,
                            node.getVariableMapping().get(variable));
                }
            }
            return rewrittenVariableMappingBuilder.build();
        }

        private ImmutableList<PlanNode> rewriteSetOperationSubPlans(SetOperationNode node, RewriteContext<Set<Symbol>> context, ListMultimap<VariableReferenceExpression, VariableReferenceExpression> rewrittenVariableMapping)
        {
            // Find the corresponding input symbol to the remaining output symbols and prune the subplans
            ImmutableList.Builder<PlanNode> rewrittenSubPlans = ImmutableList.builder();
            for (int i = 0; i < node.getSources().size(); i++) {
                ImmutableSet.Builder<Symbol> expectedInputSymbols = ImmutableSet.builder();
                for (Collection<VariableReferenceExpression> variables : rewrittenVariableMapping.asMap().values()) {
                    expectedInputSymbols.add(new Symbol(Iterables.get(variables, i).getName()));
                }
                rewrittenSubPlans.add(context.rewrite(node.getSources().get(i), expectedInputSymbols.build()));
            }
            return rewrittenSubPlans.build();
        }

        @Override
        public PlanNode visitValues(ValuesNode node, RewriteContext<Set<Symbol>> context)
        {
            ImmutableList.Builder<Symbol> rewrittenOutputSymbolsBuilder = ImmutableList.builder();
            ImmutableList.Builder<ImmutableList.Builder<RowExpression>> rowBuildersBuilder = ImmutableList.builder();
            // Initialize builder for each row
            for (int i = 0; i < node.getRows().size(); i++) {
                rowBuildersBuilder.add(ImmutableList.builder());
            }
            ImmutableList<ImmutableList.Builder<RowExpression>> rowBuilders = rowBuildersBuilder.build();
            for (int i = 0; i < node.getOutputSymbols().size(); i++) {
                Symbol outputSymbol = node.getOutputSymbols().get(i);
                // If output symbol is used
                if (context.get().contains(outputSymbol)) {
                    rewrittenOutputSymbolsBuilder.add(outputSymbol);
                    // Add the value of the output symbol for each row
                    for (int j = 0; j < node.getRows().size(); j++) {
                        rowBuilders.get(j).add(node.getRows().get(j).get(i));
                    }
                }
            }
            ImmutableList.Builder<VariableReferenceExpression> rewrittenOutputVariablesBuilder = ImmutableList.builder();
            Set<String> nameContext = context.get().stream().map(Symbol::getName).collect(toImmutableSet());
            for (int i = 0; i < node.getOutputVariables().size(); i++) {
                VariableReferenceExpression outputVariable = node.getOutputVariables().get(i);
                // If output symbol is used
                if (nameContext.contains(outputVariable.getName())) {
                    rewrittenOutputVariablesBuilder.add(outputVariable);
                }
            }
            List<List<RowExpression>> rewrittenRows = rowBuilders.stream()
                    .map(ImmutableList.Builder::build)
                    .collect(toImmutableList());
            return new ValuesNode(node.getId(), rewrittenOutputSymbolsBuilder.build(), rewrittenOutputVariablesBuilder.build(), rewrittenRows);
        }

        @Override
        public PlanNode visitApply(ApplyNode node, RewriteContext<Set<Symbol>> context)
        {
            // remove unused apply nodes
            if (intersection(node.getSubqueryAssignments().getSymbols(), context.get()).isEmpty()) {
                return context.rewrite(node.getInput(), context.get());
            }

            // extract symbols required subquery plan
            ImmutableSet.Builder<Symbol> subqueryAssignmentsSymbolsBuilder = ImmutableSet.builder();
            Assignments.Builder subqueryAssignments = Assignments.builder();
            for (Map.Entry<Symbol, Expression> entry : node.getSubqueryAssignments().getMap().entrySet()) {
                Symbol output = entry.getKey();
                Expression expression = entry.getValue();
                if (context.get().contains(output)) {
                    subqueryAssignmentsSymbolsBuilder.addAll(SymbolsExtractor.extractUnique(expression));
                    subqueryAssignments.put(output, expression);
                }
            }

            Set<Symbol> subqueryAssignmentsSymbols = subqueryAssignmentsSymbolsBuilder.build();
            PlanNode subquery = context.rewrite(node.getSubquery(), subqueryAssignmentsSymbols);

            // prune not used correlation symbols
            Set<Symbol> subquerySymbols = SymbolsExtractor.extractUnique(subquery);
            List<Symbol> newCorrelation = node.getCorrelation().stream()
                    .filter(subquerySymbols::contains)
                    .collect(toImmutableList());

            Set<Symbol> inputContext = ImmutableSet.<Symbol>builder()
                    .addAll(context.get())
                    .addAll(newCorrelation)
                    .addAll(subqueryAssignmentsSymbols) // need to include those: e.g: "expr" from "expr IN (SELECT 1)"
                    .build();
            PlanNode input = context.rewrite(node.getInput(), inputContext);
            return new ApplyNode(node.getId(), input, subquery, subqueryAssignments.build(), newCorrelation, node.getOriginSubqueryError());
        }

        @Override
        public PlanNode visitAssignUniqueId(AssignUniqueId node, RewriteContext<Set<Symbol>> context)
        {
            if (!context.get().contains(node.getIdVariableAsSymbol())) {
                return context.rewrite(node.getSource(), context.get());
            }
            return context.defaultRewrite(node, context.get());
        }

        @Override
        public PlanNode visitLateralJoin(LateralJoinNode node, RewriteContext<Set<Symbol>> context)
        {
            PlanNode subquery = context.rewrite(node.getSubquery(), context.get());

            // remove unused lateral nodes
            if (intersection(ImmutableSet.copyOf(subquery.getOutputSymbols()), context.get()).isEmpty() && isScalar(subquery)) {
                return context.rewrite(node.getInput(), context.get());
            }

            // prune not used correlation symbols
            Set<Symbol> subquerySymbols = SymbolsExtractor.extractUnique(subquery);
            List<Symbol> newCorrelation = node.getCorrelation().stream()
                    .filter(subquerySymbols::contains)
                    .collect(toImmutableList());

            Set<Symbol> inputContext = ImmutableSet.<Symbol>builder()
                    .addAll(context.get())
                    .addAll(newCorrelation)
                    .build();
            PlanNode input = context.rewrite(node.getInput(), inputContext);

            // remove unused lateral nodes
            if (intersection(ImmutableSet.copyOf(input.getOutputSymbols()), inputContext).isEmpty() && isScalar(input)) {
                return subquery;
            }

            return new LateralJoinNode(node.getId(), input, subquery, newCorrelation, node.getType(), node.getOriginSubqueryError());
        }
    }
}