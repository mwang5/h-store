/* This file is part of VoltDB.
* Copyright (C) 2008-2010 VoltDB Inc.
*
* Permission is hereby granted, free of charge, to any person obtaining
* a copy of this software and associated documentation files (the
* "Software"), to deal in the Software without restriction, including
* without limitation the rights to use, copy, modify, merge, publish,
* distribute, sublicense, and/or sell copies of the Software, and to
* permit persons to whom the Software is furnished to do so, subject to
* the following conditions:
*
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
* IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
* OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
* ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
* OTHER DEALINGS IN THE SOFTWARE.
*/

package org.voltdb.regressionsuites;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.voltdb.BackendTarget;
import org.voltdb.DefaultSnapshotDataTarget;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltTableRow;
import org.voltdb.VoltType;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Cluster;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Site;
import org.voltdb.catalog.Table;
import org.voltdb.client.Client;
import org.voltdb.client.ProcCallException;
import org.voltdb.utils.SnapshotVerifier;
import org.voltdb.utils.SnapshotConverter;
import org.voltdb.regressionsuites.saverestore.CatalogChangeSingleProcessServer;
import org.voltdb.regressionsuites.saverestore.SaveRestoreTestProjectBuilder;

import edu.brown.catalog.CatalogUtil;
import edu.brown.hstore.HStore;

/**
* Test the SnapshotSave and SnapshotRestore system procedures
*/
public class TestSaveRestoreSysprocSuite extends RegressionSuite {

    private static final String TMPDIR = "/tmp";
    private static final String TESTNONCE = "testnonce";
    private static final int ALLOWEXPORT = 0;

    public TestSaveRestoreSysprocSuite(String name) 
    {
        super(name);
    }

    @Override
    public void setUp() 
    {
        deleteTestFiles();
        super.setUp();
        DefaultSnapshotDataTarget.m_simulateFullDiskWritingChunk = false;
        DefaultSnapshotDataTarget.m_simulateFullDiskWritingHeader = false;
        org.voltdb.sysprocs.SnapshotRegistry.clear();
    }

    @Override
    public void tearDown() 
    {
        deleteTestFiles();
        try {
            super.tearDown();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void deleteTestFiles() 
    {
        FilenameFilter cleaner = new FilenameFilter()
        {
            public boolean accept(File dir, String file)
            {
                return file.startsWith(TESTNONCE) ||
                file.endsWith(".vpt") ||
                file.endsWith(".digest") ||
                file.endsWith(".tsv") ||
                file.endsWith(".csv");
            }
        };

        File tmp_dir = new File(TMPDIR);
        File[] tmp_files = tmp_dir.listFiles(cleaner);
        for (File tmp_file : tmp_files)
        {
            tmp_file.delete();
        }
    }
    
    private void corruptTestFiles(java.util.Random r) throws Exception
    {
        FilenameFilter cleaner = new FilenameFilter()
        {
            public boolean accept(File dir, String file)
            {
                return file.startsWith(TESTNONCE);
            }
        };

        File tmp_dir = new File(TMPDIR);
        File[] tmp_files = tmp_dir.listFiles(cleaner);
        ArrayList<File> files = new ArrayList<File>();
        for(int i = 0; i < tmp_files.length; i++) {
            if (tmp_files[i].length() == 0) 
                continue;
            else
                files.add(tmp_files[i]);
        }
        Object[] tmp_files1 = new Object[]{files};
        File[] tmp_files2 = files.toArray(new File[files.size()]);
        //int tmpIndex = r.nextInt(tmp_files.length);
        int tmpIndex = r.nextInt(tmp_files2.length);
        byte corruptValue[] = new byte[1];
        r.nextBytes(corruptValue);
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile( tmp_files2[tmpIndex], "rw");
        System.out.println((int)raf.length());
        int corruptPosition = r.nextInt((int)raf.length());
        raf.seek(corruptPosition);
        byte currentValue = raf.readByte();
        while (currentValue == corruptValue[0]) {
            r.nextBytes(corruptValue);
        }
        System.out.println("Corrupting file " + tmp_files2[tmpIndex].getName() +
                " at byte " + corruptPosition + " with value " + corruptValue[0]);
        raf.seek(corruptPosition);
        raf.write(corruptValue);
        raf.close();
    }
    
    private VoltTable createReplicatedTable(int numberOfItems,
            int indexBase,
            StringBuilder sb) {
        return createReplicatedTable(numberOfItems, indexBase, sb, false);
    }
    
    private VoltTable createReplicatedTable(int numberOfItems,
                                            int indexBase,
                                            StringBuilder sb,
                                            boolean generateCSV)
    {
        VoltTable repl_table =
            new VoltTable(new ColumnInfo("RT_ID", VoltType.INTEGER),
                          new ColumnInfo("RT_NAME", VoltType.STRING),
                          new ColumnInfo("RT_INTVAL", VoltType.INTEGER),
                          new ColumnInfo("RT_FLOATVAL", VoltType.FLOAT));
        char delimeter = generateCSV ? ',' : '\t';
        for (int i = indexBase; i < numberOfItems + indexBase; i++) {
            String stringVal = null;
            String escapedVal = null;

            if (sb != null) {
                if (generateCSV) {
                    int escapable = i % 5;
                    switch (escapable) {
                    case 0:
                        stringVal = "name_" + i;
                        escapedVal = "name_" + i;
                        break;
                    case 1:
                        stringVal = "na,me_" + i;
                        escapedVal = "\"na,me_" + i + "\"";
                        break;
                    case 2:
                        stringVal = "na\"me_" + i;
                        escapedVal = "\"na\"\"me_" + i + "\"";
                        break;
                    case 3:
                        stringVal = "na\rme_" + i;
                        escapedVal = "\"na\rme_" + i + "\"";
                        break;
                    case 4:
                        stringVal = "na\nme_" + i;
                        escapedVal = "\"na\nme_" + i + "\"";
                        break;
                    }
                } else {
                    int escapable = i % 5;
                    switch (escapable) {
                    case 0:
                        stringVal = "name_" + i;
                        escapedVal = "name_" + i;
                        break;
                    case 1:
                        stringVal = "na\tme_" + i;
                        escapedVal = "na\\tme_" + i;
                        break;
                    case 2:
                        stringVal = "na\nme_" + i;
                        escapedVal = "na\\nme_" + i;
                        break;
                    case 3:
                        stringVal = "na\rme_" + i;
                        escapedVal = "na\\rme_" + i;
                        break;
                    case 4:
                        stringVal = "na\\me_" + i;
                        escapedVal = "na\\\\me_" + i;
                        break;
                    }
                }
            } else {
                stringVal = "name_" + i;
            }

            Object[] row = new Object[] {i,
                                         stringVal,
                                         i,
                                         new Double(i)};
            if (sb != null) {
                sb.append(i).append(delimeter).append(escapedVal).append(delimeter);
                sb.append(i).append(delimeter).append(new Double(i).toString()).append('\n');
            }
            repl_table.addRow(row);
        }
        return repl_table;
    }
    
    private VoltTable createPartitionedTable(int numberOfItems, int indexBase)
    {
        VoltTable partition_table =
                new VoltTable(new ColumnInfo("PT_ID", VoltType.INTEGER),
                              new ColumnInfo("PT_NAME", VoltType.STRING),
                              new ColumnInfo("PT_INTVAL", VoltType.INTEGER),
                              new ColumnInfo("PT_FLOATVAL", VoltType.FLOAT));

        for (int i = indexBase; i < numberOfItems + indexBase; i++)
        {
            Object[] row = new Object[] {i,
                                         "name_" + i,
                                         i,
                                         new Double(i)};
            partition_table.addRow(row);
        }
        return partition_table;
    }

    private VoltTable[] loadTable(Client client, String tableName, VoltTable table)
    {
        VoltTable[] results = null;
        try
        {
            client.callProcedure("@LoadMultipartitionTable", tableName, table);
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
            fail("loadTable exception: " + ex.getMessage());
        }
        return results;
    }

    private void loadLargeReplicatedTable(Client client, String tableName,
            int itemsPerChunk, int numChunks) {
        loadLargeReplicatedTable(client, tableName, itemsPerChunk, numChunks, false, null);
    }

    private void loadLargeReplicatedTable(Client client, String tableName,
                                          int itemsPerChunk, int numChunks, boolean generateCSV, StringBuilder sb)
    {
        for (int i = 0; i < numChunks; i++)
        {
            VoltTable repl_table =
                createReplicatedTable(itemsPerChunk, i * itemsPerChunk, sb, generateCSV);
            loadTable(client, tableName, repl_table);
        }
        if (sb != null) {
            sb.trimToSize();
        }
    }
    
    private void loadLargePartitionedTable(Client client, String tableName,
            int itemsPerChunk, int numChunks)
    {
        for (int i = 0; i < numChunks; i++)
        {
            VoltTable part_table =
                    createPartitionedTable(itemsPerChunk, i * itemsPerChunk);
            loadTable(client, tableName, part_table);
        }
    }

    private VoltTable[] saveTables(Client client)
    {
        VoltTable[] results = null;
        try
        {
            results = client.callProcedure("@SnapshotSave", TMPDIR,
                                           TESTNONCE,
                                           (byte)1).getResults();
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
            fail("SnapshotSave exception: " + ex.getMessage());
        }
        return results;
    }
    
    private void checkTable(Client client, String tableName, String orderByCol,
            int expectedRows)
    {
        if (expectedRows > 200000)
        {
            System.out.println("Table too large to retrieve with select *");
            System.out.println("Skipping integrity check");
        }
        VoltTable result = null;
        try
        {
            result = client.callProcedure("SaveRestoreSelect", tableName).getResults()[0];
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        final int rowCount = result.getRowCount();
        assertEquals(expectedRows, rowCount);

        int i = 0;
        while (result.advanceRow())
        {
            assertEquals(i, result.getLong(0));
            assertEquals("name_" + i, result.getString(1));
            assertEquals(i, result.getLong(2));
            assertEquals(new Double(i), result.getDouble(3));
            ++i;
        }
    }

    private void validateSnapshot(boolean expectSuccess) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        PrintStream original = System.out;
        try {
            System.setOut(ps);
            String args[] = new String[] {
                    TESTNONCE,
                    "--dir",
                    TMPDIR
            };
            SnapshotVerifier.main(args);
            ps.flush();
            String reportString = baos.toString("UTF-8");
            if (expectSuccess) {
                assertTrue(reportString.startsWith("Snapshot valid\n"));
            } else {
                assertTrue(reportString.startsWith("Snapshot corrupted\n"));
            }
        } catch (UnsupportedEncodingException e) {}
          finally {
            System.setOut(original);
        }
    }
    
    /*
    * Also does some basic smoke tests
    * of @SnapshotSave, @SnapshotScan
    */
    public void testSnapshotSaveandSnapshotScan() throws Exception
    {
        System.out.println("Starting testSnapshotSave");
        Client client = this.getClient();

        int num_replicated_items_per_chunk = 100;
        int num_replicated_chunks = 10;
        int num_partitioned_items_per_chunk = 120;
        int num_partitioned_chunks = 10;

        loadLargeReplicatedTable(client, "REPLICATED_TESTER",
                                 num_replicated_items_per_chunk,
                                 num_replicated_chunks);
        loadLargePartitionedTable(client, "PARTITION_TESTER",
                                  num_partitioned_items_per_chunk,
                                  num_partitioned_chunks);
        /*test for SnapshotSave*/
        VoltTable[] results = null;
        results = client.callProcedure("@SnapshotSave", TMPDIR, TESTNONCE, (byte)1).getResults();
        assertNotNull(results);
        validateSnapshot(true);

        
        /*test for SnapshotScan*/
        VoltTable scanResults[] = client.callProcedure("@SnapshotScan", new Object[] { null }).getResults();
        assertNotNull(scanResults);
        assertEquals( 1, scanResults.length);
        assertEquals( 1, scanResults[0].getColumnCount());
        assertEquals( 1, scanResults[0].getRowCount());
        assertTrue( scanResults[0].advanceRow());
        assertTrue( "ERR_MSG".equals(scanResults[0].getColumnName(0)));

        scanResults = client.callProcedure("@SnapshotScan", "/doesntexist").getResults();
        assertNotNull(scanResults);
        assertEquals(0, scanResults[0].getRowCount());
        assertTrue( scanResults[1].advanceRow());
        assertTrue( "FAILURE".equals(scanResults[1].getString("RESULT")));

        scanResults = client.callProcedure("@SnapshotScan", TMPDIR).getResults();
        assertNotNull(scanResults);
        assertEquals( 3, scanResults.length);
        assertEquals( 8, scanResults[0].getColumnCount());
        assertTrue(scanResults[1].getRowCount() >= 1);
        assertTrue(scanResults[1].advanceRow());
        assertTrue( "SUCCESS".equals(scanResults[1].getString("RESULT")));

        
        /*
        * We can't assert that all snapshot files are generated by this test.
        * There might be leftover snapshot files from other runs.
        */
        int count = 0;
        String completeStatus = null;
        while (scanResults[0].advanceRow())
        {
            if (TESTNONCE.equals(scanResults[0].getString("NONCE"))) {
                assertTrue(TMPDIR.equals(scanResults[0].getString("PATH")));
                count++;
                completeStatus = scanResults[0].getString("COMPLETE");
            }
        }
        assertEquals(1, count);
        assertNotNull(completeStatus);
        assertTrue("TRUE".equals(completeStatus));        
        
        FilenameFilter cleaner = new FilenameFilter()
        {
            public boolean accept(File dir, String file)
            {
                return file.startsWith(TESTNONCE) && file.endsWith("vpt");
            }
        };

        File tmp_dir = new File(TMPDIR);
        File[] tmp_files = tmp_dir.listFiles(cleaner);
        for (int i = 0; i < tmp_files.length; i++) {
            if (tmp_files[i].length() == 0) 
                tmp_files[i].delete();
        }
        File[] tmp_files_1 = tmp_dir.listFiles(cleaner);
        tmp_files_1[0].delete();
        
        scanResults = client.callProcedure("@SnapshotScan", TMPDIR).getResults();
        assertNotNull(scanResults);
        assertEquals( 3, scanResults.length);
        assertEquals( 8, scanResults[0].getColumnCount());
        assertTrue(scanResults[0].getRowCount() >= 1);
        assertTrue(scanResults[0].advanceRow());
        count = 0;
        String missingTableName = null;
        do {
            if (TESTNONCE.equals(scanResults[0].getString("NONCE"))
                && "FALSE".equals(scanResults[0].getString("COMPLETE"))) {
                assertTrue(TMPDIR.equals(scanResults[0].getString("PATH")));
                count++;
                missingTableName = scanResults[0].getString("TABLES_MISSING");
            }
        } while (scanResults[0].advanceRow());
        assertEquals(1, count);
        assertNotNull(missingTableName);
        assertTrue(tmp_files_1[0].getName().contains(missingTableName));
    }

    /**
    * Build a list of the tests to be run. Use the regression suite
    * helpers to allow multiple back ends.
    * JUnit magic that uses the regression suite helper classes.
    */
    static public junit.framework.Test suite() {
        
        MultiConfigSuiteBuilder builder =
                new MultiConfigSuiteBuilder(TestSaveRestoreSysprocSuite.class);
            SaveRestoreTestProjectBuilder project =
                new SaveRestoreTestProjectBuilder();  
            VoltServerConfig config = null;

            project.addAllDefaults();
              
            config =
                new LocalSingleProcessServer("sysproc-threesites.jar", 3,
                                                     BackendTarget.NATIVE_EE_JNI);
            boolean success = config.compile(project);
            assert(success);
            builder.addServerConfig(config);

            return builder;
    }
}