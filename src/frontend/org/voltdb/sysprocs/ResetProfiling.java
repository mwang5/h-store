package org.voltdb.sysprocs;

import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.voltdb.BackendTarget;
import org.voltdb.DependencySet;
import org.voltdb.HsqlBackend;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.catalog.Procedure;
import org.voltdb.exceptions.ServerFaultException;
import org.voltdb.types.TimestampType;
import org.voltdb.utils.VoltTableUtil;

import edu.brown.hstore.ClientInterface;
import edu.brown.hstore.PartitionExecutor;
import edu.brown.hstore.conf.HStoreConf;
import edu.brown.hstore.wal.CommandLogWriter;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.ProfileMeasurement;

/** 
 * Reset internal profiling statistics
 */
@ProcInfo(singlePartition = false)
public class ResetProfiling extends VoltSystemProcedure {
    private static final Logger LOG = Logger.getLogger(ResetProfiling.class);

    public static final ColumnInfo nodeResultsColumns[] = {
        new ColumnInfo("SITE", VoltType.STRING),
        new ColumnInfo("STATUS", VoltType.STRING),
        new ColumnInfo("CREATED", VoltType.TIMESTAMP),
    };
    
    private final ProfileMeasurement gcTime = new ProfileMeasurement(this.getClass().getSimpleName());

    @Override
    public void globalInit(PartitionExecutor site, Procedure catalog_proc,
            BackendTarget eeType, HsqlBackend hsql, PartitionEstimator p_estimator) {
        super.globalInit(site, catalog_proc, eeType, hsql, p_estimator);
        site.registerPlanFragment(SysProcFragmentId.PF_resetProfilingAggregate, this);
        site.registerPlanFragment(SysProcFragmentId.PF_resetProfilingDistribute, this);
    }

    @Override
    public DependencySet executePlanFragment(long txn_id,
                                             Map<Integer, List<VoltTable>> dependencies,
                                             int fragmentId,
                                             ParameterSet params,
                                             PartitionExecutor.SystemProcedureExecutionContext context) {
        DependencySet result = null;
        switch (fragmentId) {
            // Reset Stats
            case SysProcFragmentId.PF_resetProfilingDistribute: {
                LOG.info("Resetting internal profiling counters");
                HStoreConf hstore_conf = hstore_site.getHStoreConf();
                
                // EXECUTOR
                if (hstore_conf.site.exec_profiling) {
                    this.executor.getWorkExecTime().reset();
                    this.executor.getWorkIdleTime().reset();
                    this.executor.getWorkNetworkTime().reset();
                    this.executor.getWorkUtilityTime().reset();
                }
                
                // The first partition at this HStoreSite will have to reset
                // any global profling parameters
                if (this.isFirstLocalPartition()) {
                    
                    // NETWORK
                    if (hstore_conf.site.network_profiling) {
                        ClientInterface ci = hstore_site.getClientInterface();
                        ci.getNetworkProcessing().reset();
                        ci.getNetworkBackPressureOff().reset();
                        ci.getNetworkBackPressureOn().reset();
                    }
                    
                    // COMMAND LOGGER
                    if (hstore_conf.site.commandlog_profiling) {
                        CommandLogWriter commandLog = hstore_site.getCommandLogWriter();
                        commandLog.getLoggerWritingTime().reset();
                        commandLog.getLoggerBlockedTime().reset();
                        commandLog.getLoggerNetworkTime().reset();
                    }
                }
                
                
                VoltTable vt = new VoltTable(nodeResultsColumns);
                vt.addRow(this.executor.getHStoreSite().getSiteName(),
                          this.gcTime.getTotalThinkTimeMS() + " ms",
                          new TimestampType());
                result = new DependencySet(SysProcFragmentId.PF_resetProfilingDistribute, vt);
                break;
            }
            // Aggregate Results
            case SysProcFragmentId.PF_resetProfilingAggregate:
                List<VoltTable> siteResults = dependencies.get(SysProcFragmentId.PF_resetProfilingDistribute);
                if (siteResults == null || siteResults.isEmpty()) {
                    String msg = "Missing site results";
                    throw new ServerFaultException(msg, txn_id);
                }
                
                VoltTable vt = VoltTableUtil.combine(siteResults);
                result = new DependencySet(SysProcFragmentId.PF_resetProfilingAggregate, vt);
                break;
            default:
                String msg = "Unexpected sysproc fragmentId '" + fragmentId + "'";
                throw new ServerFaultException(msg, txn_id);
        } // SWITCH
        // Invalid!
        return (result);
    }

    public VoltTable[] run() {
        return this.autoDistribute(SysProcFragmentId.PF_resetProfilingDistribute,
                                   SysProcFragmentId.PF_resetProfilingAggregate);
    }
}
