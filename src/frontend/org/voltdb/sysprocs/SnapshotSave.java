/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.sysprocs;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.voltdb.*;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.catalog.*;
import org.voltdb.client.ConnectionUtil;
import org.voltdb.dtxn.DtxnConstants;
import org.voltdb.sysprocs.saverestore.SnapshotUtil;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.PartitionExecutor;
import edu.brown.hstore.PartitionExecutor.SystemProcedureExecutionContext;
import edu.brown.utils.CollectionUtil;
import edu.brown.utils.PartitionEstimator;

@ProcInfo(singlePartition = false)
public class SnapshotSave extends VoltSystemProcedure
{
    private static final Logger LOG = Logger.getLogger(SnapshotSave.class);

    private static final int DEP_saveTest = (int)
        SysProcFragmentId.PF_saveTest | DtxnConstants.MULTIPARTITION_DEPENDENCY;
    private static final int DEP_saveTestResults = (int)
        SysProcFragmentId.PF_saveTestResults;
    private static final int DEP_createSnapshotTargets = (int)
        SysProcFragmentId.PF_createSnapshotTargets | DtxnConstants.MULTIPARTITION_DEPENDENCY;
    private static final int DEP_createSnapshotTargetsResults = (int)
        SysProcFragmentId.PF_createSnapshotTargetsResults;

    public static final ColumnInfo nodeResultsColumns[] =
        new ColumnInfo[] {
            new ColumnInfo(CNAME_HOST_ID, CTYPE_ID),
            new ColumnInfo("HOSTNAME", VoltType.STRING),
            new ColumnInfo("TABLE", VoltType.STRING),
            new ColumnInfo("RESULT", VoltType.STRING),
            new ColumnInfo("ERR_MSG", VoltType.STRING)
    };

    public static final ColumnInfo partitionResultsColumns[] =
        new ColumnInfo[] {
                          new ColumnInfo(CNAME_HOST_ID, CTYPE_ID),
                          new ColumnInfo("HOSTNAME", VoltType.STRING),
                          new ColumnInfo(CNAME_SITE_ID, CTYPE_ID),
                          new ColumnInfo("RESULT", VoltType.STRING),
                          new ColumnInfo("ERR_MSG", VoltType.STRING)
    };

    public static final VoltTable constructNodeResultsTable()
    {
        return new VoltTable(nodeResultsColumns);
    }

    public static final VoltTable constructPartitionResultsTable()
    {
        return new VoltTable(partitionResultsColumns);
    }

    @Override
    public void globalInit(PartitionExecutor site, Procedure catalog_proc,
            BackendTarget eeType, HsqlBackend hsql, PartitionEstimator p_estimator) {
        super.globalInit(site, catalog_proc, eeType, hsql, p_estimator);
        site.registerPlanFragment(SysProcFragmentId.PF_saveTest, this);
        site.registerPlanFragment(SysProcFragmentId.PF_saveTestResults, this);
        site.registerPlanFragment(SysProcFragmentId.PF_createSnapshotTargets, this);
        site.registerPlanFragment(SysProcFragmentId.PF_createSnapshotTargetsResults, this);
    }

    @Override
    public DependencySet
    executePlanFragment(long txn_id,
                        Map<Integer, List<VoltTable>> dependencies,
                        int fragmentId,
                        ParameterSet params,
                        SystemProcedureExecutionContext context)
    {
        String hostname = ConnectionUtil.getHostnameOrAddress();
        if (fragmentId == SysProcFragmentId.PF_saveTest)
        {
            assert(params.toArray()[0] != null);
            assert(params.toArray()[1] != null);
            String file_path = (String) params.toArray()[0];
            String file_nonce = (String) params.toArray()[1];
            return saveTest(file_path, file_nonce, context, hostname);
        }
        else if (fragmentId == SysProcFragmentId.PF_saveTestResults)
        {
            return saveTestResults(dependencies);
        }
        else if (fragmentId == SysProcFragmentId.PF_createSnapshotTargets)
        {
            assert(params.toArray()[0] != null);
            assert(params.toArray()[1] != null);
            assert(params.toArray()[2] != null);
            assert(params.toArray()[3] != null);
            final String file_path = (String) params.toArray()[0];
            final String file_nonce = (String) params.toArray()[1];
            final long startTime = (Long)params.toArray()[2];
            byte block = (Byte)params.toArray()[3];
            SnapshotSaveAPI saveAPI = new SnapshotSaveAPI();
            VoltTable result = saveAPI.startSnapshotting(file_path, file_nonce, block, startTime, context, hostname);
            return new DependencySet(SnapshotSave.DEP_createSnapshotTargets, result);
        }
        else if (fragmentId == SysProcFragmentId.PF_createSnapshotTargetsResults)
        {
            return createSnapshotTargetsResults(dependencies);
        }
        assert (false);
        return null;
    }

    private DependencySet createSnapshotTargetsResults(
            Map<Integer, List<VoltTable>> dependencies) {
        {
            LOG.trace("Aggregating create snapshot target results");
            assert (dependencies.size() > 0);
            List<VoltTable> dep = dependencies.get(DEP_createSnapshotTargets);
            VoltTable result = null;
            for (VoltTable table : dep)
            {
                /**
                 * XXX Ning: There are two different tables here. We have to
                 * detect which table we are looking at in order to create the
                 * result table with the proper schema. Maybe we should make the
                 * result table consistent?
                 */
                if (result == null) {
                    if (table.getColumnType(2).equals(VoltType.INTEGER))
                        result = constructPartitionResultsTable();
                    else
                        result = constructNodeResultsTable();
                }

                while (table.advanceRow())
                {
                    // this will add the active row of table
                    result.add(table);
                }
            }
            return new
                DependencySet( DEP_createSnapshotTargetsResults, result);
        }
    }

    private DependencySet saveTest(String file_path, String file_nonce,
            SystemProcedureExecutionContext context, String hostname) {
        {
            VoltTable result = constructNodeResultsTable();
            // Choose the lowest site ID on this host to do the file scan
            // All other sites should just return empty results tables.
            Host catalog_host = context.getHost();
            Site catalog_site = CollectionUtil.first(CatalogUtil.getSitesForHost(catalog_host));
            Integer lowest_site_id = catalog_site.getId();
            if (context.getExecutionSite().getSiteId() == lowest_site_id)
            {
                LOG.trace("Checking feasibility of save with path and nonce: "
                                + file_path + ", " + file_nonce);

                if (SnapshotSiteProcessor.ExecutionSitesCurrentlySnapshotting.get() != -1) {
                    result.addRow(
                                  context.getHost().getId(),
                                  hostname,
                                  "",
                                  "FAILURE",
                    "SNAPSHOT IN PROGRESS");
                    return new DependencySet( DEP_saveTest, result);
                }

                for (Table table : SnapshotUtil.getTablesToSave(context.getDatabase()))
                {
                    File saveFilePath =
                        SnapshotUtil.constructFileForTable(table, file_path, file_nonce,
                                              context.getSite().getHost().getTypeName(), context.getExecutionSite());
                    LOG.trace("Host ID " + context.getHost().getId() +
                                    " table: " + table.getTypeName() +
                                    " to path: " + saveFilePath);
                    String file_valid = "SUCCESS";
                    String err_msg = "";
                    if (saveFilePath.exists())
                    {
                        file_valid = "FAILURE";
                        err_msg = "SAVE FILE ALREADY EXISTS: " + saveFilePath;
                    }
                    else if (!saveFilePath.getParentFile().canWrite())
                    {
                        file_valid = "FAILURE";
                        err_msg = "FILE LOCATION UNWRITABLE: " + saveFilePath;
                    }
                    else
                    {
                        try
                        {
                            saveFilePath.createNewFile();
                        }
                        catch (IOException ex)
                        {
                            file_valid = "FAILURE";
                            err_msg = "FILE CREATION OF " + saveFilePath +
                            "RESULTED IN IOException: " + ex.getMessage();
                        }
                    }
                    result.addRow(catalog_host.getId(),
                                  hostname,
                                  table.getTypeName(),
                                  file_valid,
                                  err_msg);
                }
            }
            return new DependencySet(DEP_saveTest, result);
        }
    }

    private DependencySet saveTestResults(
            Map<Integer, List<VoltTable>> dependencies) {
        {
            LOG.trace("Aggregating save feasiblity results");
            assert (dependencies.size() > 0);
            List<VoltTable> dep = dependencies.get(DEP_saveTest);
            VoltTable result = constructNodeResultsTable();
            for (VoltTable table : dep)
            {
                while (table.advanceRow())
                {
                    // this will add the active row of table
                    result.add(table);
                }
            }
            return new DependencySet( DEP_saveTestResults, result);
        }
    }

    public VoltTable[] run(String path, String nonce, long block) throws VoltAbortException
    {
        final long startTime = System.currentTimeMillis();
        LOG.info("Saving database to path: " + path + ", ID: " + nonce + " at " + startTime);

        if (path == null || path.equals("")) {
            ColumnInfo[] result_columns = new ColumnInfo[1];
            int ii = 0;
            result_columns[ii++] = new ColumnInfo("ERR_MSG", VoltType.STRING);
            VoltTable results[] = new VoltTable[] { new VoltTable(result_columns) };
            results[0].addRow("Provided path was null or the empty string");
            return results;
        }

        if (nonce == null || nonce.equals("")) {
            ColumnInfo[] result_columns = new ColumnInfo[1];
            int ii = 0;
            result_columns[ii++] = new ColumnInfo("ERR_MSG", VoltType.STRING);
            VoltTable results[] = new VoltTable[] { new VoltTable(result_columns) };
            results[0].addRow("Provided nonce was null or the empty string");
            return results;
        }

        if (nonce.contains("-") || nonce.contains(",")) {
            ColumnInfo[] result_columns = new ColumnInfo[1];
            int ii = 0;
            result_columns[ii++] = new ColumnInfo("ERR_MSG", VoltType.STRING);
            VoltTable results[] = new VoltTable[] { new VoltTable(result_columns) };
            results[0].addRow("Provided nonce " + nonce + " contains a prohitibited character (- or ,)");
            return results;
        }

        // See if we think the save will succeed
        VoltTable[] results;
        results = performSaveFeasibilityWork( DEP_saveTest, 
                                                                     DEP_saveTestResults, 
                                                                     path, nonce);
        LOG.info("performSaveFeasibilityWork Results:\n" + results[0]);

        // Test feasibility results for fail
        while (results[0].advanceRow())
        {
            if (results[0].getString("RESULT").equals("FAILURE"))
            {
                // Something lost, bomb out and just return the whole
                // table of results to the client for analysis
                return results;
            }
        }

        results = performSnapshotCreationWork( path, nonce, startTime, (byte)block);
        
        final long finishTime = System.currentTimeMillis();
        final long duration = finishTime - startTime;
        LOG.info("Snapshot initiation took " + duration + " milliseconds");
        return results;
    }

    protected final VoltTable[] autoDistribute(int distributeId, 
                                                                        int aggregateId, 
                                                                        String filePath, 
                                                                        String fileNonce) {
        
        final int num_sites = CatalogUtil.getNumberOfSites(this.database);
        final SynthesizedPlanFragment pfs[] = new SynthesizedPlanFragment[num_sites + 1];
        final ParameterSet params = new ParameterSet();
        
        int i = 0;
        for (Site catalog_site : CatalogUtil.getAllSites(this.database)) {
            Partition catalog_part = CollectionUtil.first(catalog_site.getPartitions());
            pfs[i] = new SynthesizedPlanFragment();
            pfs[i].fragmentId = SysProcFragmentId.PF_saveTest;
            pfs[i].inputDependencyIds = new int[] { };
            pfs[i].outputDependencyIds = new int[] { distributeId };
            pfs[i].multipartition = true;
            pfs[i].nonExecSites = false;
            pfs[i].destPartitionId = catalog_part.getId();
            pfs[i].parameters = params;
            params.setParameters(filePath, fileNonce);
            pfs[i].last_task = (catalog_site.getId() == hstore_site.getSiteId());
            i += 1;
        } // FOR

        // a final plan fragment to aggregate the results
        pfs[i] = new SynthesizedPlanFragment();
        pfs[i].fragmentId = SysProcFragmentId.PF_saveTestResults;
        pfs[i].inputDependencyIds = new int[] { distributeId };
        pfs[i].outputDependencyIds = new int[] { aggregateId };
        pfs[i].multipartition = false;
        pfs[i].nonExecSites = false;
        pfs[i].destPartitionId = CollectionUtil.first(hstore_site.getLocalPartitionIds());
        pfs[i].parameters = params;
        pfs[i].last_task = true;
        return (this.executeSysProcPlanFragments(pfs, aggregateId));
    }
    
    private final VoltTable[] performSaveFeasibilityWork(int distributeId, 
                                                                                         int aggregateId, 
                                                                                         String filePath,
                                                                                         String fileNonce)
    {
        VoltTable[] results;
        results = autoDistribute(distributeId, 
                                              aggregateId, 
                                              filePath, 
                                              fileNonce); 
        return results;
    }

    private final VoltTable[] performSnapshotCreationWork(String filePath,
            String fileNonce,
            long startTime,
            byte block)
    {
        SynthesizedPlanFragment[] pfs = new SynthesizedPlanFragment[2];

        // This fragment causes each execution site to confirm the likely
        // success of writing tables to disk
        pfs[0] = new SynthesizedPlanFragment();
        pfs[0].fragmentId = SysProcFragmentId.PF_createSnapshotTargets;
        pfs[0].outputDependencyIds = new int[]{ DEP_createSnapshotTargets };
        pfs[0].inputDependencyIds = new int[] {};
        pfs[0].multipartition = true;
        ParameterSet params = new ParameterSet();
        params.setParameters(filePath, fileNonce, startTime, block);
        pfs[0].parameters = params;

        // This fragment aggregates the save-to-disk sanity check results
        pfs[1] = new SynthesizedPlanFragment();
        pfs[1].fragmentId = SysProcFragmentId.PF_createSnapshotTargetsResults;
        pfs[1].outputDependencyIds = new int[]{ DEP_createSnapshotTargetsResults };
        pfs[1].inputDependencyIds = new int[] { DEP_createSnapshotTargets };
        pfs[1].multipartition = false;
        pfs[1].parameters = new ParameterSet();

        VoltTable[] results;
        results = executeSysProcPlanFragments(pfs, DEP_createSnapshotTargetsResults);
        return results;
    }
}
