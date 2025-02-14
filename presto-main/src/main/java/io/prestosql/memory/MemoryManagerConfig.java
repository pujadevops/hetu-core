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
package io.prestosql.memory;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.configuration.DefunctConfig;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.airlift.units.MinDuration;

import javax.validation.constraints.NotNull;

import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.airlift.units.DataSize.succinctBytes;
import static java.util.concurrent.TimeUnit.MINUTES;

@DefunctConfig({
        "experimental.cluster-memory-manager-enabled",
        "query.low-memory-killer.enabled",
        "resources.reserved-system-memory"})
public class MemoryManagerConfig
{
    public static final String FAULT_TOLERANT_TASK_MEMORY_CONFIG = "fault-tolerant-execution-task-memory";

    // enforced against user memory allocations
    private DataSize maxQueryMemory = new DataSize(20, GIGABYTE);
    // enforced against user + system memory allocations (default is maxQueryMemory * 2)
    private DataSize maxQueryTotalMemory;
    private DataSize faultTolerantExecutionTaskMemory = new DataSize(5, GIGABYTE);
    private double faultTolerantExecutionTaskMemoryGrowthFactor = 3.0;
    private double faultTolerantExecutionTaskMemoryEstimationQuantile = 0.9;
    private DataSize faultTolerantExecutionTaskRuntimeMemoryEstimationOverhead = new DataSize(1, GIGABYTE);
    private String lowMemoryKillerPolicy = LowMemoryKillerPolicy.NONE;
    private Duration killOnOutOfMemoryDelay = new Duration(5, MINUTES);
    private boolean isSuspendQuery;
    private int maxSuspendedQueries = 10;

    public String getLowMemoryKillerPolicy()
    {
        return lowMemoryKillerPolicy;
    }

    @Config("query.low-memory-killer.policy")
    public MemoryManagerConfig setLowMemoryKillerPolicy(String lowMemoryKillerPolicy)
    {
        this.lowMemoryKillerPolicy = lowMemoryKillerPolicy;
        return this;
    }

    @NotNull
    @MinDuration("5s")
    public Duration getKillOnOutOfMemoryDelay()
    {
        return killOnOutOfMemoryDelay;
    }

    @Config("query.low-memory-killer.delay")
    @ConfigDescription("Delay between cluster running low on memory and invoking killer")
    public MemoryManagerConfig setKillOnOutOfMemoryDelay(Duration killOnOutOfMemoryDelay)
    {
        this.killOnOutOfMemoryDelay = killOnOutOfMemoryDelay;
        return this;
    }

    @NotNull
    public DataSize getMaxQueryMemory()
    {
        return maxQueryMemory;
    }

    @Config("query.max-memory")
    public MemoryManagerConfig setMaxQueryMemory(DataSize maxQueryMemory)
    {
        this.maxQueryMemory = maxQueryMemory;
        return this;
    }

    @NotNull
    public DataSize getMaxQueryTotalMemory()
    {
        if (maxQueryTotalMemory == null) {
            return succinctBytes(maxQueryMemory.toBytes() * 2);
        }
        return maxQueryTotalMemory;
    }

    @Config("query.max-total-memory")
    public MemoryManagerConfig setMaxQueryTotalMemory(DataSize maxQueryTotalMemory)
    {
        this.maxQueryTotalMemory = maxQueryTotalMemory;
        return this;
    }

    public boolean getSuspendQueryEnabled()
    {
        return this.isSuspendQuery;
    }

    @Config("query.suspend-query-enabled")
    public MemoryManagerConfig setSuspendQueryEnabled(boolean isEnabled)
    {
        this.isSuspendQuery = isEnabled;
        return this;
    }

    public int getMaxSuspendedQueries()
    {
        return this.maxSuspendedQueries;
    }

    @Config("query.max-suspended-queries")
    public MemoryManagerConfig setMaxSuspendedQueries(int queryCount)
    {
        this.maxSuspendedQueries = queryCount;
        return this;
    }

    @NotNull
    public DataSize getFaultTolerantExecutionTaskMemory()
    {
        return faultTolerantExecutionTaskMemory;
    }

    @Config(FAULT_TOLERANT_TASK_MEMORY_CONFIG)
    @ConfigDescription("Estimated amount of memory a single task will use when task level retries are used; value is used allocating nodes for tasks execution")
    public MemoryManagerConfig setFaultTolerantExecutionTaskMemory(DataSize faultTolerantExecutionTaskMemory)
    {
        this.faultTolerantExecutionTaskMemory = faultTolerantExecutionTaskMemory;
        return this;
    }

    @NotNull
    public double getFaultTolerantExecutionTaskMemoryGrowthFactor()
    {
        return faultTolerantExecutionTaskMemoryGrowthFactor;
    }

    @Config("fault-tolerant-execution-task-memory-growth-factor")
    @ConfigDescription("Factor by which estimated task memory is increased if task execution runs out of memory; value is used allocating nodes for tasks execution")
    public MemoryManagerConfig setFaultTolerantExecutionTaskMemoryGrowthFactor(double faultTolerantExecutionTaskMemoryGrowthFactor)
    {
        checkArgument(faultTolerantExecutionTaskMemoryGrowthFactor >= 1.0, "faultTolerantExecutionTaskMemoryGrowthFactor must not be less than 1.0");
        this.faultTolerantExecutionTaskMemoryGrowthFactor = faultTolerantExecutionTaskMemoryGrowthFactor;
        return this;
    }

    @NotNull
    public double getFaultTolerantExecutionTaskMemoryEstimationQuantile()
    {
        return faultTolerantExecutionTaskMemoryEstimationQuantile;
    }

    @Config("fault-tolerant-execution-task-memory-estimation-quantile")
    @ConfigDescription("What quantile of memory usage of completed tasks to look at when estimating memory usage for upcoming tasks")
    public MemoryManagerConfig setFaultTolerantExecutionTaskMemoryEstimationQuantile(double faultTolerantExecutionTaskMemoryEstimationQuantile)
    {
        checkArgument(faultTolerantExecutionTaskMemoryEstimationQuantile >= 0.0 && faultTolerantExecutionTaskMemoryEstimationQuantile <= 1.0,
                "fault-tolerant-execution-task-memory-estimation-quantile must not be in [0.0, 1.0] range");
        this.faultTolerantExecutionTaskMemoryEstimationQuantile = faultTolerantExecutionTaskMemoryEstimationQuantile;
        return this;
    }

    @NotNull
    public DataSize getFaultTolerantExecutionTaskRuntimeMemoryEstimationOverhead()
    {
        return faultTolerantExecutionTaskRuntimeMemoryEstimationOverhead;
    }

    @Config("fault-tolerant-execution-task-runtime-memory-estimation-overhead")
    @ConfigDescription("Extra memory to account for when estimating actual task runtime memory consumption")
    public MemoryManagerConfig setFaultTolerantExecutionTaskRuntimeMemoryEstimationOverhead(DataSize faultTolerantExecutionTaskRuntimeMemoryEstimationOverhead)
    {
        this.faultTolerantExecutionTaskRuntimeMemoryEstimationOverhead = faultTolerantExecutionTaskRuntimeMemoryEstimationOverhead;
        return this;
    }

    public static class LowMemoryKillerPolicy
    {
        public static final String NONE = "none";
        public static final String TOTAL_RESERVATION = "total-reservation";
        public static final String TOTAL_RESERVATION_ON_BLOCKED_NODES = "total-reservation-on-blocked-nodes";
    }
}
