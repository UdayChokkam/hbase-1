/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.zookeeper;

import com.google.common.base.Stopwatch;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.NotAllMetaRegionsOnlineException;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.RetriesExhaustedException;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.ipc.RpcClient;
import org.apache.hadoop.hbase.ipc.ServerNotRunningYetException;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.AdminService;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.protobuf.generated.ZooKeeperProtos;
import org.apache.hadoop.hbase.regionserver.RegionServerStoppedException;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.zookeeper.KeeperException;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.rmi.UnknownHostException;

/**
 * Utility class to perform operation (get/wait for/verify/set/delete) on znode in ZooKeeper
 * which keeps hbase:meta region server location.
 *
 * Stateless class with a bunch of static methods. Doesn't manage resources passed in
 * (e.g. HConnection, ZooKeeperWatcher etc).
 *
 * Meta region location is set by <code>RegionServerServices</code>.
 * This class doesn't use ZK watchers, rather accesses ZK directly.
 *
 * This class it stateless. The only reason it's not made a non-instantiable util class
 * with a collection of static methods is that it'd be rather hard to mock properly in tests.
 *
 * TODO: rewrite using RPC calls to master to find out about hbase:meta.
 */
@InterfaceAudience.Private
public class MetaTableLocator {
  private static final Log LOG = LogFactory.getLog(MetaTableLocator.class);

  static final byte [] META_REGION_NAME =
    HRegionInfo.FIRST_META_REGIONINFO.getRegionName();

  // only needed to allow non-timeout infinite waits to stop when cluster shuts down
  private volatile boolean stopped = false;

  /**
   * Checks if the meta region location is available.
   * @return true if meta region location is available, false if not
   */
  public boolean isLocationAvailable(ZooKeeperWatcher zkw) {
    try {
      return ZKUtil.getData(zkw, zkw.metaServerZNode) != null;
    } catch(KeeperException e) {
      LOG.error("ZK error trying to get hbase:meta from ZooKeeper");
      return false;
    } catch (InterruptedException e) {
      LOG.error("ZK error trying to get hbase:meta from ZooKeeper");
      return false;
    }
  }

  /**
   * Gets the meta region location, if available.  Does not block.
   * @param zkw zookeeper connection to use
   * @return server name or null if we failed to get the data.
   */
  public ServerName getMetaRegionLocation(final ZooKeeperWatcher zkw) {
    try {
      try {
        return ServerName.parseFrom(ZKUtil.getData(zkw, zkw.metaServerZNode));
      } catch (DeserializationException e) {
        throw ZKUtil.convert(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    } catch (KeeperException ke) {
        return null;
    }
  }

  /**
   * Gets the meta region location, if available, and waits for up to the
   * specified timeout if not immediately available.
   * Given the zookeeper notification could be delayed, we will try to
   * get the latest data.
   * @param timeout maximum time to wait, in millis
   * @return server name for server hosting meta region formatted as per
   * {@link ServerName}, or null if none available
   * @throws InterruptedException if interrupted while waiting
   */
  public ServerName waitMetaRegionLocation(ZooKeeperWatcher zkw, long timeout)
  throws InterruptedException, NotAllMetaRegionsOnlineException {
    try {
      if (ZKUtil.checkExists(zkw, zkw.baseZNode) == -1) {
        String errorMsg = "Check the value configured in 'zookeeper.znode.parent'. "
            + "There could be a mismatch with the one configured in the master.";
        LOG.error(errorMsg);
        throw new IllegalArgumentException(errorMsg);
      }
    } catch (KeeperException e) {
      throw new IllegalStateException("KeeperException while trying to check baseZNode:", e);
    }
    ServerName sn = blockUntilAvailable(zkw, timeout);

    if (sn == null) {
      throw new NotAllMetaRegionsOnlineException("Timed out; " + timeout + "ms");
    }

    return sn;
  }

  /**
   * Waits indefinitely for availability of <code>hbase:meta</code>.  Used during
   * cluster startup.  Does not verify meta, just that something has been
   * set up in zk.
   * @see #waitMetaRegionLocation(org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher, long)
   * @throws InterruptedException if interrupted while waiting
   */
  public void waitMetaRegionLocation(ZooKeeperWatcher zkw) throws InterruptedException {
    Stopwatch stopwatch = new Stopwatch().start();
    while (!stopped) {
      try {
        if (waitMetaRegionLocation(zkw, 100) != null) break;
        long sleepTime = stopwatch.elapsedMillis();
        // +1 in case sleepTime=0
        if ((sleepTime + 1) % 10000 == 0) {
          LOG.warn("Have been waiting for meta to be assigned for " + sleepTime + "ms");
        }
      } catch (NotAllMetaRegionsOnlineException e) {
        if (LOG.isTraceEnabled()) {
          LOG.trace("hbase:meta still not available, sleeping and retrying." +
            " Reason: " + e.getMessage());
        }
      }
    }
  }

  /**
   * Verify <code>hbase:meta</code> is deployed and accessible.
   * @param timeout How long to wait on zk for meta address (passed through to
   * the internal call to {@link #getMetaServerConnection}.
   * @return True if the <code>hbase:meta</code> location is healthy.
   * @throws java.io.IOException
   * @throws InterruptedException
   */
  public boolean verifyMetaRegionLocation(HConnection hConnection,
      ZooKeeperWatcher zkw, final long timeout)
  throws InterruptedException, IOException {
    AdminProtos.AdminService.BlockingInterface service = null;
    try {
      service = getMetaServerConnection(hConnection, zkw, timeout);
    } catch (NotAllMetaRegionsOnlineException e) {
      // Pass
    } catch (ServerNotRunningYetException e) {
      // Pass -- remote server is not up so can't be carrying root
    } catch (UnknownHostException e) {
      // Pass -- server name doesn't resolve so it can't be assigned anything.
    } catch (RegionServerStoppedException e) {
      // Pass -- server name sends us to a server that is dying or already dead.
    }
    return (service == null)? false:
      verifyRegionLocation(service,
          getMetaRegionLocation(zkw), META_REGION_NAME);
  }

  /**
   * Verify we can connect to <code>hostingServer</code> and that its carrying
   * <code>regionName</code>.
   * @param hostingServer Interface to the server hosting <code>regionName</code>
   * @param address The servername that goes with the <code>metaServer</code>
   * Interface.  Used logging.
   * @param regionName The regionname we are interested in.
   * @return True if we were able to verify the region located at other side of
   * the Interface.
   * @throws IOException
   */
  // TODO: We should be able to get the ServerName from the AdminProtocol
  // rather than have to pass it in.  Its made awkward by the fact that the
  // HRI is likely a proxy against remote server so the getServerName needs
  // to be fixed to go to a local method or to a cache before we can do this.
  private boolean verifyRegionLocation(AdminService.BlockingInterface hostingServer,
      final ServerName address, final byte [] regionName)
  throws IOException {
    if (hostingServer == null) {
      LOG.info("Passed hostingServer is null");
      return false;
    }
    Throwable t;
    try {
      // Try and get regioninfo from the hosting server.
      return ProtobufUtil.getRegionInfo(hostingServer, regionName) != null;
    } catch (ConnectException e) {
      t = e;
    } catch (RetriesExhaustedException e) {
      t = e;
    } catch (RemoteException e) {
      IOException ioe = e.unwrapRemoteException();
      t = ioe;
    } catch (IOException e) {
      Throwable cause = e.getCause();
      if (cause != null && cause instanceof EOFException) {
        t = cause;
      } else if (cause != null && cause.getMessage() != null
          && cause.getMessage().contains("Connection reset")) {
        t = cause;
      } else {
        t = e;
      }
    }
    LOG.info("Failed verification of " + Bytes.toStringBinary(regionName) +
      " at address=" + address + ", exception=" + t);
    return false;
  }

  /**
   * Gets a connection to the server hosting meta, as reported by ZooKeeper,
   * waiting up to the specified timeout for availability.
   * <p>WARNING: Does not retry.  Use an {@link org.apache.hadoop.hbase.client.HTable} instead.
   * @param timeout How long to wait on meta location
   * @return connection to server hosting meta
   * @throws InterruptedException
   * @throws NotAllMetaRegionsOnlineException if timed out waiting
   * @throws IOException
   */
  private AdminService.BlockingInterface getMetaServerConnection(HConnection hConnection,
      ZooKeeperWatcher zkw, long timeout)
  throws InterruptedException, NotAllMetaRegionsOnlineException, IOException {
    return getCachedConnection(hConnection, waitMetaRegionLocation(zkw, timeout));
  }

  /**
   * @param sn ServerName to get a connection against.
   * @return The AdminProtocol we got when we connected to <code>sn</code>
   * May have come from cache, may not be good, may have been setup by this
   * invocation, or may be null.
   * @throws IOException
   */
  @SuppressWarnings("deprecation")
  private static AdminService.BlockingInterface getCachedConnection(HConnection hConnection,
    ServerName sn)
  throws IOException {
    if (sn == null) {
      return null;
    }
    AdminService.BlockingInterface service = null;
    try {
      service = hConnection.getAdmin(sn);
    } catch (RetriesExhaustedException e) {
      if (e.getCause() != null && e.getCause() instanceof ConnectException) {
        // Catch this; presume it means the cached connection has gone bad.
      } else {
        throw e;
      }
    } catch (SocketTimeoutException e) {
      LOG.debug("Timed out connecting to " + sn);
    } catch (NoRouteToHostException e) {
      LOG.debug("Connecting to " + sn, e);
    } catch (SocketException e) {
      LOG.debug("Exception connecting to " + sn);
    } catch (UnknownHostException e) {
      LOG.debug("Unknown host exception connecting to  " + sn);
    } catch (RpcClient.FailedServerException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Server " + sn + " is in failed server list.");
      }
    } catch (IOException ioe) {
      Throwable cause = ioe.getCause();
      if (ioe instanceof ConnectException) {
        // Catch. Connect refused.
      } else if (cause != null && cause instanceof EOFException) {
        // Catch. Other end disconnected us.
      } else if (cause != null && cause.getMessage() != null &&
        cause.getMessage().toLowerCase().contains("connection reset")) {
        // Catch. Connection reset.
      } else {
        throw ioe;
      }

    }
    return service;
  }

  /**
   * Sets the location of <code>hbase:meta</code> in ZooKeeper to the
   * specified server address.
   * @param zookeeper zookeeper reference
   * @param location The server hosting <code>hbase:meta</code>
   * @throws KeeperException unexpected zookeeper exception
   */
  public static void setMetaLocation(ZooKeeperWatcher zookeeper,
                                     final ServerName location)
  throws KeeperException {
    LOG.info("Setting hbase:meta region location in ZooKeeper as " + location);
    // Make the MetaRegionServer pb and then get its bytes and save this as
    // the znode content.
    byte [] data = toByteArray(location);
    try {
      ZKUtil.createAndWatch(zookeeper, zookeeper.metaServerZNode, data);
    } catch(KeeperException.NodeExistsException nee) {
      LOG.debug("META region location already existed, updated location");
      ZKUtil.setData(zookeeper, zookeeper.metaServerZNode, data);
    }
  }

  /**
   * Build up the znode content.
   * @param sn What to put into the znode.
   * @return The content of the meta-region-server znode
   */
  private static byte [] toByteArray(final ServerName sn) {
    // ZNode content is a pb message preceded by some pb magic.
    HBaseProtos.ServerName pbsn =
      HBaseProtos.ServerName.newBuilder()
                            .setHostName(sn.getHostname())
                            .setPort(sn.getPort())
                            .setStartCode(sn.getStartcode())
                            .build();

    ZooKeeperProtos.MetaRegionServer pbrsr =
      ZooKeeperProtos.MetaRegionServer.newBuilder()
                                      .setServer(pbsn)
                                      .setRpcVersion(HConstants.RPC_CURRENT_VERSION)
                                      .build();
    return ProtobufUtil.prependPBMagic(pbrsr.toByteArray());
  }

  /**
   * Deletes the location of <code>hbase:meta</code> in ZooKeeper.
   * @param zookeeper zookeeper reference
   * @throws KeeperException unexpected zookeeper exception
   */
  public void deleteMetaLocation(ZooKeeperWatcher zookeeper)
  throws KeeperException {
    LOG.info("Unsetting hbase:meta region location in ZooKeeper");
    try {
      // Just delete the node.  Don't need any watches.
      ZKUtil.deleteNode(zookeeper, zookeeper.metaServerZNode);
    } catch(KeeperException.NoNodeException nne) {
      // Has already been deleted
    }
  }

  /**
   * Wait until the meta region is available.
   * @param zkw zookeeper connection to use
   * @param timeout maximum time to wait, in millis
   * @return ServerName or null if we timed out.
   * @throws InterruptedException
   */
  public ServerName blockUntilAvailable(final ZooKeeperWatcher zkw,
      final long timeout)
  throws InterruptedException {
    byte [] data = ZKUtil.blockUntilAvailable(zkw, zkw.metaServerZNode, timeout);
    if (data == null) return null;
    try {
      return ServerName.parseFrom(data);
    } catch (DeserializationException e) {
      LOG.warn("Failed parse", e);
      return null;
    }
  }

  /**
   * Stop working.
   * Interrupts any ongoing waits.
   */
  public void stop() {
    if (!stopped) {
      LOG.debug("Stopping MetaTableLocator");
      stopped = true;
    }
  }
}
