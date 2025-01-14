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
package io.prestosql.sql.planner.optimizations;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.expressions.RowExpressionRewriter;
import io.prestosql.expressions.RowExpressionTreeRewriter;
import io.prestosql.spi.block.SortOrder;
import io.prestosql.spi.plan.AggregationNode;
import io.prestosql.spi.plan.AggregationNode.Aggregation;
import io.prestosql.spi.plan.LimitNode;
import io.prestosql.spi.plan.OrderingScheme;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.plan.PlanNodeIdAllocator;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.plan.TopNNode;
import io.prestosql.spi.relation.CallExpression;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.relation.VariableReferenceExpression;
import io.prestosql.sql.planner.PartitioningScheme;
import io.prestosql.sql.planner.SymbolUtils;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.plan.CubeFinishNode;
import io.prestosql.sql.planner.plan.StatisticAggregations;
import io.prestosql.sql.planner.plan.StatisticAggregationsDescriptor;
import io.prestosql.sql.planner.plan.StatisticsWriterNode;
import io.prestosql.sql.planner.plan.TableExecuteNode;
import io.prestosql.sql.planner.plan.TableFinishNode;
import io.prestosql.sql.planner.plan.TableWriterNode;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.ExpressionRewriter;
import io.prestosql.sql.tree.ExpressionTreeRewriter;
import io.prestosql.sql.tree.SymbolReference;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.prestosql.spi.plan.AggregationNode.groupingSets;
import static io.prestosql.sql.planner.SymbolUtils.toSymbolReference;
import static io.prestosql.sql.relational.OriginalExpressionUtils.castToExpression;
import static io.prestosql.sql.relational.OriginalExpressionUtils.castToRowExpression;
import static io.prestosql.sql.relational.OriginalExpressionUtils.isExpression;
import static java.util.Objects.requireNonNull;

public class SymbolMapper
{
    private final Map<String, String> mapping;
    private TypeProvider types;

    public SymbolMapper(Map<String, String> mapping, TypeProvider types)
    {
        requireNonNull(mapping, "mapping is null");
        this.mapping = mapping;
        this.types = types;
    }

    public static SymbolMapper symbolMapper(Map<Symbol, Symbol> mapping)
    {
        return null;
    }

    public void setTypes(TypeProvider types)
    {
        this.types = types;
    }

    public TypeProvider getTypes()
    {
        return types;
    }

    public Symbol map(Symbol symbol)
    {
        String canonical = symbol.getName();
        while (mapping.containsKey(canonical) && !mapping.get(canonical).equals(canonical)) {
            canonical = mapping.get(canonical);
        }
        return new Symbol(canonical);
    }

    public TableExecuteNode map(TableExecuteNode node, PlanNode source)
    {
        return map(node, source, node.getId());
    }

    public TableExecuteNode map(TableExecuteNode node, PlanNode source, PlanNodeId newId)
    {
        // Intentionally does not use mapAndDistinct on columns as that would remove columns
        return new TableExecuteNode(
                newId,
                source,
                node.getTarget(),
                map(node.getRowCountSymbol()),
                map(node.getFragmentSymbol()),
                map(node.getColumns()),
                node.getColumnNames(),
                node.getPartitioningScheme().map(partitioningScheme -> map(partitioningScheme, source.getOutputSymbols())),
                node.getPreferredPartitioningScheme().map(partitioningScheme -> map(partitioningScheme, source.getOutputSymbols())));
    }

    public PartitioningScheme map(PartitioningScheme scheme, List<Symbol> sourceLayout)
    {
        return new PartitioningScheme(
                scheme.getPartitioning().translate(this::map),
                mapAndDistinct(sourceLayout),
                scheme.getHashColumn().map(this::map),
                scheme.isReplicateNullsAndAny(),
                scheme.getBucketToPartition());
    }

    public VariableReferenceExpression map(VariableReferenceExpression variable)
    {
        String canonical = variable.getName();
        while (mapping.containsKey(canonical) && !mapping.get(canonical).equals(canonical)) {
            canonical = mapping.get(canonical);
        }
        if (canonical.equals(variable.getName())) {
            return variable;
        }
        return new VariableReferenceExpression(canonical, types.get(new Symbol(canonical)));
    }

    public RowExpression map(RowExpression value)
    {
        if (isExpression(value)) {
            return castToRowExpression(map(castToExpression(value)));
        }
        return RowExpressionTreeRewriter.rewriteWith(new RowExpressionRewriter<Void>()
        {
            @Override
            public RowExpression rewriteVariableReference(VariableReferenceExpression variable, Void context, RowExpressionTreeRewriter<Void> treeRewriter)
            {
                return map(variable);
            }
        }, value);
    }

    public Expression map(Expression value)
    {
        return ExpressionTreeRewriter.rewriteWith(new ExpressionRewriter<Void>()
        {
            @Override
            public Expression rewriteSymbolReference(SymbolReference node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
            {
                Symbol canonical = map(SymbolUtils.from(node));
                return toSymbolReference(canonical);
            }
        }, value);
    }

    public AggregationNode map(AggregationNode node, PlanNode source)
    {
        return map(node, source, node.getId());
    }

    public AggregationNode map(AggregationNode node, PlanNode source, PlanNodeIdAllocator idAllocator)
    {
        return map(node, source, idAllocator.getNextId());
    }

    private AggregationNode map(AggregationNode node, PlanNode source, PlanNodeId newNodeId)
    {
        ImmutableMap.Builder<Symbol, Aggregation> aggregations = ImmutableMap.builder();
        for (Entry<Symbol, Aggregation> entry : node.getAggregations().entrySet()) {
            aggregations.put(map(entry.getKey()), map(entry.getValue()));
        }

        return new AggregationNode(
                newNodeId,
                source,
                aggregations.build(),
                groupingSets(
                        mapAndDistinct(node.getGroupingKeys()),
                        node.getGroupingSetCount(),
                        node.getGlobalGroupingSets()),
                ImmutableList.of(),
                node.getStep(),
                node.getHashSymbol().map(this::map),
                node.getGroupIdSymbol().map(this::map),
                node.getAggregationType(),
                node.getFinalizeSymbol());
    }

    private Aggregation map(Aggregation aggregation)
    {
        return new Aggregation(
                new CallExpression(
                        aggregation.getFunctionCall().getDisplayName(),
                        aggregation.getFunctionCall().getFunctionHandle(),
                        aggregation.getFunctionCall().getType(),
                        aggregation.getArguments().stream()
                                .map(this::map)
                                .collect(toImmutableList())),
                aggregation.getArguments().stream()
                        .map(this::map)
                        .collect(toImmutableList()),
                aggregation.isDistinct(),
                aggregation.getFilter().map(this::map),
                aggregation.getOrderingScheme().map(this::map),
                aggregation.getMask().map(this::map));
    }

    public TopNNode map(TopNNode node, PlanNode source, PlanNodeId newNodeId)
    {
        return new TopNNode(
                newNodeId,
                source,
                node.getCount(),
                map(node.getOrderingScheme()),
                node.getStep());
    }

    public LimitNode map(LimitNode node, PlanNode source)
    {
        return new LimitNode(
                node.getId(),
                source,
                node.getCount(),
                node.getTiesResolvingScheme().map(this::map),
                node.isPartial());
    }

    public TableWriterNode map(TableWriterNode node, PlanNode source)
    {
        return map(node, source, node.getId());
    }

    public TableWriterNode map(TableWriterNode node, PlanNode source, PlanNodeId newNodeId)
    {
        // Intentionally does not use canonicalizeAndDistinct as that would remove columns
        ImmutableList<Symbol> columns = node.getColumns().stream()
                .map(this::map)
                .collect(toImmutableList());

        return new TableWriterNode(
                newNodeId,
                source,
                node.getTarget(),
                map(node.getRowCountSymbol()),
                map(node.getFragmentSymbol()),
                columns,
                node.getColumnNames(),
                node.getPartitioningScheme().map(partitioningScheme -> canonicalize(partitioningScheme, source)),
                node.getStatisticsAggregation().map(this::map),
                node.getStatisticsAggregationDescriptor().map(this::map));
    }

    public StatisticsWriterNode map(StatisticsWriterNode node, PlanNode source)
    {
        return new StatisticsWriterNode(
                node.getId(),
                source,
                node.getTarget(),
                node.getRowCountSymbol(),
                node.isRowCountEnabled(),
                node.getDescriptor().map(this::map));
    }

    public TableFinishNode map(TableFinishNode node, PlanNode source)
    {
        return new TableFinishNode(
                node.getId(),
                source,
                node.getTarget(),
                map(node.getRowCountSymbol()),
                node.getStatisticsAggregation().map(this::map),
                node.getStatisticsAggregationDescriptor().map(descriptor -> descriptor.map(this::map)));
    }

    public CubeFinishNode map(CubeFinishNode node, PlanNode source)
    {
        return new CubeFinishNode(
                node.getId(),
                source,
                map(node.getRowCountSymbol()),
                node.getMetadata(),
                node.getPredicateColumnsType());
    }

    private PartitioningScheme canonicalize(PartitioningScheme scheme, PlanNode source)
    {
        return new PartitioningScheme(
                scheme.getPartitioning().translate(this::map),
                mapAndDistinct(source.getOutputSymbols()),
                scheme.getHashColumn().map(this::map),
                scheme.isReplicateNullsAndAny(),
                scheme.getBucketToPartition());
    }

    private StatisticAggregations map(StatisticAggregations statisticAggregations)
    {
        Map<Symbol, Aggregation> aggregations = statisticAggregations.getAggregations().entrySet().stream()
                .collect(toImmutableMap(entry -> map(entry.getKey()), entry -> map(entry.getValue())));
        return new StatisticAggregations(aggregations, mapAndDistinct(statisticAggregations.getGroupingSymbols()));
    }

    private StatisticAggregationsDescriptor<Symbol> map(StatisticAggregationsDescriptor<Symbol> descriptor)
    {
        return descriptor.map(this::map);
    }

    private List<Symbol> map(List<Symbol> outputs)
    {
        return outputs.stream()
                .map(this::map)
                .collect(toImmutableList());
    }

    private List<Symbol> mapAndDistinct(List<Symbol> outputs)
    {
        Set<Symbol> added = new HashSet<>();
        ImmutableList.Builder<Symbol> builder = ImmutableList.builder();
        for (Symbol symbol : outputs) {
            Symbol canonical = map(symbol);
            if (added.add(canonical)) {
                builder.add(canonical);
            }
        }
        return builder.build();
    }

    private OrderingScheme map(OrderingScheme orderingScheme)
    {
        ImmutableList.Builder<Symbol> symbols = ImmutableList.builder();
        ImmutableMap.Builder<Symbol, SortOrder> orderings = ImmutableMap.builder();
        Set<Symbol> seenCanonicals = new HashSet<>(orderingScheme.getOrderBy().size());
        for (Symbol symbol : orderingScheme.getOrderBy()) {
            Symbol canonical = map(symbol);
            if (seenCanonicals.add(canonical)) {
                symbols.add(canonical);
                orderings.put(canonical, orderingScheme.getOrdering(symbol));
            }
        }
        return new OrderingScheme(symbols.build(), orderings.build());
    }

    public static SymbolMapper.Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private final ImmutableMap.Builder<String, String> mappings = ImmutableMap.builder();
        private TypeProvider types;

        public SymbolMapper build()
        {
            return new SymbolMapper(mappings.build(), types);
        }

        public void put(Symbol from, VariableReferenceExpression to)
        {
            mappings.put(from.getName(), to.getName());
        }

        public void put(Symbol from, Symbol to)
        {
            mappings.put(from.getName(), to.getName());
        }

        public void putTypes(TypeProvider types)
        {
            this.types = types;
        }
    }
}
