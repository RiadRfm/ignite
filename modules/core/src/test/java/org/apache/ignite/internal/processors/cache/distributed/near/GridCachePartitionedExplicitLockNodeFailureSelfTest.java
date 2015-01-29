/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.near;

import org.apache.ignite.cache.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.events.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.testframework.junits.common.*;

import java.util.*;

import static org.apache.ignite.cache.CacheAtomicityMode.*;
import static org.apache.ignite.cache.CacheMode.*;
import static org.apache.ignite.cache.CacheDistributionMode.*;
import static org.apache.ignite.events.IgniteEventType.*;

/**
 * Tests for node failure in transactions.
 */
public class GridCachePartitionedExplicitLockNodeFailureSelfTest extends GridCommonAbstractTest {
    /** */
    private static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    /** */
    public static final int GRID_CNT = 4;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(gridName);

        c.getTransactionsConfiguration().setTxSerializableEnabled(true);

        TcpDiscoverySpi disco = new TcpDiscoverySpi();

        disco.setIpFinder(ipFinder);

        c.setDiscoverySpi(disco);

        CacheConfiguration cc = defaultCacheConfiguration();

        cc.setCacheMode(PARTITIONED);
        cc.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        cc.setBackups(GRID_CNT - 1);
        cc.setAtomicityMode(TRANSACTIONAL);
        cc.setDistributionMode(NEAR_PARTITIONED);

        c.setCacheConfiguration(cc);

        return c;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        stopAllGrids();
    }

    /** @throws Exception If check failed. */
    @SuppressWarnings("ErrorNotRethrown")
    public void testLockFromNearOrBackup() throws Exception {
        startGrids(GRID_CNT);

        int idx = 0;

        info("Grid will be stopped: " + idx);

        Integer key = 0;

        while (grid(idx).mapKeyToNode(null, key).id().equals(grid(0).localNode().id()))
            key++;

        ClusterNode node = grid(idx).mapKeyToNode(null, key);

        info("Primary node for key [id=" + node.id() + ", order=" + node.order() + ", key=" + key + ']');

        GridCache<Integer, String> cache = cache(idx);

        cache.put(key, "val");

        assert cache.lock(key, -1);

        for (int checkIdx = 1; checkIdx < GRID_CNT; checkIdx++) {
            info("Check grid index: " + checkIdx);

            GridCache<Integer, String> checkCache = cache(checkIdx);

            assert !checkCache.lock(key, -1);

            CacheEntry e = checkCache.entry(key);

            assert e.isLocked() : "Entry is not locked for grid [idx=" + checkIdx + ", entry=" + e + ']';
        }

        Collection<IgniteInternalFuture<?>> futs = new LinkedList<>();

        for (int i = 1; i < GRID_CNT; i++) {
            futs.add(
                waitForLocalEvent(grid(i).events(), new P1<IgniteEvent>() {
                    @Override public boolean apply(IgniteEvent e) {
                        info("Received grid event: " + e);

                        return true;
                    }
                }, EVT_NODE_LEFT));
        }

        stopGrid(idx);

        for (IgniteInternalFuture<?> fut : futs)
            fut.get();

        for (int i = 0; i < 3; i++) {
            try {
                for (int checkIdx = 1; checkIdx < GRID_CNT; checkIdx++) {
                    info("Check grid index: " + checkIdx);

                    GridCache<Integer, String> checkCache = cache(checkIdx);

                    CacheEntry e = checkCache.entry(key);

                    info("Checking entry: " + e);

                    assert !e.isLocked() : "Entry is locked for grid [idx=" + checkIdx + ", entry=" + e + ']';
                }
            }
            catch (AssertionError e) {
                if (i == 2)
                    throw e;

                U.warn(log, "Check failed (will retry in 1000 ms): " + e.getMessage());

                U.sleep(1000);

                continue;
            }

            // Check passed on all grids.
            break;
        }
    }
}
