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
package io.prestosql.plugin.hive.orc;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Closer;
import io.airlift.log.Logger;
import io.prestosql.memory.context.AggregatedMemoryContext;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.orc.OrcCorruptionException;
import io.prestosql.orc.OrcDataSource;
import io.prestosql.orc.OrcDataSourceId;
import io.prestosql.orc.OrcRecordReader;
import io.prestosql.plugin.hive.FileFormatDataSourceStats;
import io.prestosql.plugin.hive.HiveColumnHandle;
import io.prestosql.plugin.hive.HiveUpdateProcessor;
import io.prestosql.plugin.hive.orc.OrcDeletedRows.MaskDeletedRowsFunction;
import io.prestosql.spi.Page;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.LazyBlock;
import io.prestosql.spi.block.LazyBlockLoader;
import io.prestosql.spi.block.LongArrayBlock;
import io.prestosql.spi.block.RunLengthEncodedBlock;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.type.Type;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.plugin.base.util.Closables.closeAllSuppress;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_BAD_DATA;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_CURSOR_ERROR;
import static io.prestosql.plugin.hive.HiveUpdatablePageSource.BUCKET_CHANNEL;
import static io.prestosql.plugin.hive.HiveUpdatablePageSource.ORIGINAL_TRANSACTION_CHANNEL;
import static io.prestosql.plugin.hive.HiveUpdatablePageSource.ROW_ID_CHANNEL;
import static io.prestosql.plugin.hive.OrcFileWriter.computeBucketValue;
import static io.prestosql.spi.block.RowBlock.fromFieldBlocks;
import static io.prestosql.spi.predicate.Utils.nativeValueToBlock;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class IcebergOrcPageSource
        implements ConnectorPageSource
{
    private static final Block ORIGINAL_FILE_TRANSACTION_ID_BLOCK = nativeValueToBlock(BIGINT, 0L);
    private static final Logger LOG = Logger.get(IcebergOrcPageSource.class);

    private final OrcRecordReader recordReader;
    private final List<ColumnAdaptation> columnAdaptations;
    private final OrcDataSource orcDataSource;
    private final Optional<OrcDeletedRows> deletedRows;

    private boolean closed;

    private final AggregatedMemoryContext memoryContext;
    private final LocalMemoryContext localMemoryContext;

    private final FileFormatDataSourceStats stats;

    // Row ID relative to all the original files of the same bucket ID before this file in lexicographic order
    private final Optional<Long> originalFileRowId;

    private long completedPositions;

    private Optional<Page> outstandingPage = Optional.empty();

    public IcebergOrcPageSource(
            OrcRecordReader recordReader,
            List<ColumnAdaptation> columnAdaptations,
            OrcDataSource orcDataSource,
            Optional<OrcDeletedRows> deletedRows,
            Optional<Long> originalFileRowId,
            AggregatedMemoryContext memoryContext,
            FileFormatDataSourceStats stats)
    {
        this.recordReader = requireNonNull(recordReader, "recordReader is null");
        this.columnAdaptations = ImmutableList.copyOf(requireNonNull(columnAdaptations, "columnAdaptations is null"));
        this.orcDataSource = requireNonNull(orcDataSource, "orcDataSource is null");
        this.deletedRows = requireNonNull(deletedRows, "deletedRows is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.memoryContext = requireNonNull(memoryContext, "memoryContext is null");
        this.localMemoryContext = memoryContext.newLocalMemoryContext(IcebergOrcPageSource.class.getSimpleName());
        this.originalFileRowId = requireNonNull(originalFileRowId, "originalFileRowId is null");
    }

    @Override
    public long getCompletedBytes()
    {
        return orcDataSource.getReadBytes();
    }

    @Override
    public OptionalLong getCompletedPositions()
    {
        return OptionalLong.of(completedPositions);
    }

    @Override
    public long getReadTimeNanos()
    {
        return orcDataSource.getReadTimeNanos();
    }

    @Override
    public boolean isFinished()
    {
        return closed;
    }

    @Override
    public Page getNextPage()
    {
        Page page;
        try {
            if (outstandingPage.isPresent()) {
                page = outstandingPage.get();
                outstandingPage = Optional.empty();
                // Mark no bytes consumed by outstandingPage.
                // We can reset it again below if deletedRows loading yields again.
                // In such case the brief period when it is set to 0 will not be observed externally as
                // page source memory usage is only read by engine after call to getNextPage completes.
                localMemoryContext.setBytes(0);
            }
            else {
                page = recordReader.nextPage();
            }
        }
        catch (IOException | RuntimeException e) {
            closeAllSuppress(e, this);
            throw handleException(orcDataSource.getId(), e);
        }

        if (page == null) {
            close();
            return null;
        }

        completedPositions += page.getPositionCount();

        Optional<Long> startRowId = originalFileRowId.isPresent() ?
                Optional.of(originalFileRowId.get() + recordReader.getFilePosition()) : Optional.empty();

        if (deletedRows.isPresent()) {
            boolean deletedRowsYielded = false;
            if (deletedRowsYielded) {
                outstandingPage = Optional.of(page);
                localMemoryContext.setBytes(page.getRetainedSizeInBytes());
                return null; // return control to engine so it can update memory usage for query
            }
        }

        MaskDeletedRowsFunction maskDeletedRowsFunction = deletedRows
                .map(deletedRows -> deletedRows.getMaskDeletedRowsFunction(page, startRowId))
                .orElseGet(() -> {
                    OrcDeletedRows orcDeletedRows = new OrcDeletedRows(null, Optional.empty(), null, null, null, null, Optional.empty());
                    return orcDeletedRows.getMaskDeletedRowsFunction(page, startRowId);
                });
        return getColumnAdaptationsPage(page, maskDeletedRowsFunction, recordReader.getFilePosition(), startRowId);
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return getMemoryUsage();
    }

    private Page getColumnAdaptationsPage(Page page, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId)
    {
        Block[] blocks = new Block[columnAdaptations.size()];
        for (int i = 0; i < columnAdaptations.size(); i++) {
            blocks[i] = columnAdaptations.get(i).block(page, maskDeletedRowsFunction, filePosition, startRowId);
        }
        return new Page(maskDeletedRowsFunction.getPositionCount(), blocks);
    }

    static PrestoException handleException(OrcDataSourceId dataSourceId, Exception exception)
    {
        if (exception instanceof PrestoException) {
            return (PrestoException) exception;
        }
        if (exception instanceof OrcCorruptionException) {
            return new PrestoException(HIVE_BAD_DATA, exception);
        }
        return new PrestoException(HIVE_CURSOR_ERROR, format("Failed to read ORC file: %s", dataSourceId), exception);
    }

    @Override
    public void close()
    {
        // some hive input formats are broken and bad things can happen if you close them multiple times
        if (closed) {
            return;
        }
        closed = true;

        Closer closer = Closer.create();

        closer.register(() -> {
            stats.addMaxCombinedBytesPerRow(recordReader.getMaxCombinedBytesPerRow());
            recordReader.close();
        });

        closer.register(() -> {
            if (deletedRows.isPresent()) {
                OrcDeletedRows orcDeletedRows = deletedRows.get();
                if (orcDeletedRows != null) {
                    orcDeletedRows.close();
                }
            }
        });

        try {
            closer.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        catch (IllegalStateException e) {
            LOG.debug(e.getMessage());
        }
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("orcDataSource", orcDataSource.getId())
                .add("columns", columnAdaptations)
                .toString();
    }

    @Override
    public long getMemoryUsage()
    {
        return memoryContext.getBytes();
    }

    public interface ColumnAdaptation
    {
        Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId);

        static ColumnAdaptation nullColumn(Type type)
        {
            return new NullColumn(type);
        }

        static ColumnAdaptation sourceColumn(int index)
        {
            return new SourceColumn(index);
        }

        static ColumnAdaptation rowIdColumn()
        {
            return new RowIdAdaptation();
        }

        static ColumnAdaptation originalFileRowIdColumn(long startingRowId, int bucketId)
        {
            return new OriginalFileRowIdAdaptation(startingRowId, bucketId);
        }

        static ColumnAdaptation updatedRowColumnsWithOriginalFiles(long startingRowId, int bucketId, HiveUpdateProcessor updateProcessor, List<HiveColumnHandle> dependencyColumns)
        {
            return new UpdatedRowAdaptationWithOriginalFiles(startingRowId, bucketId, updateProcessor, dependencyColumns);
        }

        static ColumnAdaptation updatedRowColumns(HiveUpdateProcessor updateProcessor, List<HiveColumnHandle> dependencyColumns)
        {
            return new UpdatedRowAdaptation(updateProcessor, dependencyColumns);
        }

        static ColumnAdaptation constantColumn(Block singleValueBlock)
        {
            return new ConstantAdaptation(singleValueBlock);
        }

        static ColumnAdaptation positionColumn()
        {
            return new PositionAdaptation();
        }
    }

    private static class NullColumn
            implements ColumnAdaptation
    {
        private final Type type;
        private final Block nullBlock;

        public NullColumn(Type type)
        {
            this.type = requireNonNull(type, "type is null");
            this.nullBlock = type.createBlockBuilder(null, 1, 0)
                    .appendNull()
                    .build();
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId)
        {
            return new RunLengthEncodedBlock(nullBlock, maskDeletedRowsFunction.getPositionCount());
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("type", type)
                    .toString();
        }
    }

    private static class SourceColumn
            implements ColumnAdaptation
    {
        private final int index;

        public SourceColumn(int index)
        {
            checkArgument(index >= 0, "index is negative");
            this.index = index;
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId)
        {
            Block block = sourcePage.getBlock(index);
            return new LazyBlock(maskDeletedRowsFunction.getPositionCount(), new MaskingBlockLoader(maskDeletedRowsFunction, block));
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("index", index)
                    .toString();
        }

        private static final class MaskingBlockLoader
                implements LazyBlockLoader
        {
            private MaskDeletedRowsFunction maskDeletedRowsFunction;
            private Block sourceBlock;

            public MaskingBlockLoader(MaskDeletedRowsFunction maskDeletedRowsFunction, Block sourceBlock)
            {
                this.maskDeletedRowsFunction = requireNonNull(maskDeletedRowsFunction, "maskDeletedRowsFunction is null");
                this.sourceBlock = requireNonNull(sourceBlock, "sourceBlock is null");
            }

            @Override
            public void load(LazyBlock block)
            {
                checkState(maskDeletedRowsFunction != null, "Already loaded");
                Block resultBlock = maskDeletedRowsFunction.apply(sourceBlock.getLoadedBlock());
                maskDeletedRowsFunction = null;
                sourceBlock = null;
                block.setBlock(resultBlock);
            }

            @Override
            public Block load()
            {
                checkState(maskDeletedRowsFunction != null, "Already loaded");

                Block resultBlock = maskDeletedRowsFunction.apply(sourceBlock.getLoadedBlock());

                maskDeletedRowsFunction = null;
                sourceBlock = null;

                return resultBlock;
            }
        }
    }

    private static class RowIdAdaptation
            implements ColumnAdaptation
    {
        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId)
        {
            Block rowBlock = maskDeletedRowsFunction.apply(fromFieldBlocks(
                    sourcePage.getPositionCount(),
                    Optional.empty(),
                    new Block[] {
                            sourcePage.getBlock(ORIGINAL_TRANSACTION_CHANNEL),
                            sourcePage.getBlock(BUCKET_CHANNEL),
                            sourcePage.getBlock(ROW_ID_CHANNEL),
                    }));
            return rowBlock;
        }
    }

    /**
     * This ColumnAdaptation creates a RowBlock column containing the three
     * ACID columms - - originalTransaction, rowId, bucket - - and
     * all the columns not changed by the UPDATE statement.
     */
    private static final class UpdatedRowAdaptation
            implements ColumnAdaptation
    {
        private final HiveUpdateProcessor updateProcessor;
        private final List<Integer> nonUpdatedSourceChannels;

        public UpdatedRowAdaptation(HiveUpdateProcessor updateProcessor, List<HiveColumnHandle> dependencyColumns)
        {
            this.updateProcessor = updateProcessor;
            this.nonUpdatedSourceChannels = updateProcessor.makeNonUpdatedSourceChannels(dependencyColumns);
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId)
        {
            return updateProcessor.createUpdateRowBlock(sourcePage, nonUpdatedSourceChannels, maskDeletedRowsFunction);
        }
    }

    /**
     * This ColumnAdaptation creates a RowBlock column containing the three
     * ACID columms derived from the startingRowId and bucketId, and a special
     * original files transaction block, plus a block containing
     * all the columns not changed by the UPDATE statement.
     */
    private static final class UpdatedRowAdaptationWithOriginalFiles
            implements ColumnAdaptation
    {
        private final long startingRowId;
        private final Block bucketBlock;
        private final HiveUpdateProcessor updateProcessor;
        private final List<Integer> nonUpdatedSourceChannels;

        public UpdatedRowAdaptationWithOriginalFiles(long startingRowId, int bucketId, HiveUpdateProcessor updateProcessor, List<HiveColumnHandle> dependencyColumns)
        {
            this.startingRowId = startingRowId;
            this.bucketBlock = nativeValueToBlock(INTEGER, Long.valueOf(computeBucketValue(bucketId, 0)));
            this.updateProcessor = requireNonNull(updateProcessor, "updateProcessor is null");
            requireNonNull(dependencyColumns, "dependencyColumns is null");
            this.nonUpdatedSourceChannels = updateProcessor.makeNonUpdatedSourceChannels(dependencyColumns);
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId)
        {
            int positionCount = sourcePage.getPositionCount();
            ImmutableList.Builder<Block> originalFilesBlockBuilder = ImmutableList.builder();
            originalFilesBlockBuilder.add(
                    new RunLengthEncodedBlock(ORIGINAL_FILE_TRANSACTION_ID_BLOCK, positionCount),
                    new RunLengthEncodedBlock(bucketBlock, positionCount),
                    createRowNumberBlock(startingRowId, filePosition, positionCount));
            for (int channel = 0; channel < sourcePage.getChannelCount(); channel++) {
                originalFilesBlockBuilder.add(sourcePage.getBlock(channel));
            }
            Page page = new Page(originalFilesBlockBuilder.build().toArray(new Block[]{}));
            return updateProcessor.createUpdateRowBlock(page, nonUpdatedSourceChannels, maskDeletedRowsFunction);
        }
    }

    private static class OriginalFileRowIdAdaptation
            implements ColumnAdaptation
    {
        private final long startingRowId;
        private final Block bucketBlock;

        public OriginalFileRowIdAdaptation(long startingRowId, int bucketId)
        {
            this.startingRowId = startingRowId;
            this.bucketBlock = nativeValueToBlock(INTEGER, Long.valueOf(computeBucketValue(bucketId, 0)));
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId)
        {
            int positionCount = sourcePage.getPositionCount();
            Block rowBlock = maskDeletedRowsFunction.apply(fromFieldBlocks(
                    positionCount,
                    Optional.empty(),
                    new Block[] {
                            new RunLengthEncodedBlock(ORIGINAL_FILE_TRANSACTION_ID_BLOCK, positionCount),
                            new RunLengthEncodedBlock(bucketBlock, positionCount),
                            createRowNumberBlock(startingRowId, filePosition, positionCount)
                    }));
            return rowBlock;
        }
    }

    private static class ConstantAdaptation
            implements ColumnAdaptation
    {
        private final Block singleValueBlock;

        public ConstantAdaptation(Block singleValueBlock)
        {
            requireNonNull(singleValueBlock, "singleValueBlock is null");
            checkArgument(singleValueBlock.getPositionCount() == 1, "ConstantColumnAdaptation singleValueBlock may only contain one position");
            this.singleValueBlock = singleValueBlock;
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId)
        {
            return new RunLengthEncodedBlock(singleValueBlock, sourcePage.getPositionCount());
        }
    }

    private static class PositionAdaptation
            implements ColumnAdaptation
    {
        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition, Optional startRowId)
        {
            checkArgument(!startRowId.isPresent(), "startRowId should not be specified when using PositionAdaptation");
            return createRowNumberBlock(0, filePosition, sourcePage.getPositionCount());
        }
    }

    private static Block createRowNumberBlock(long startingRowId, long filePosition, int positionCount)
    {
        long[] translatedRowIds = new long[positionCount];
        for (int index = 0; index < positionCount; index++) {
            translatedRowIds[index] = startingRowId + filePosition + index;
        }
        return new LongArrayBlock(positionCount, Optional.empty(), translatedRowIds);
    }
}
