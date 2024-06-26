// Copyright The Tuweni Authors
// SPDX-License-Identifier: Apache-2.0
package org.apache.tuweni.rlpx;

import org.apache.tuweni.rlpx.wire.SubProtocolIdentifier;
import org.apache.tuweni.rlpx.wire.WireConnection;

import javax.annotation.Nullable;

/** A repository managing wire connections. */
public interface WireConnectionRepository {

  /** Connection listener */
  interface Listener {

    /**
     * Callback triggered when a connection changes
     *
     * @param conn the connection change
     */
    void connectionEvent(WireConnection conn);
  }

  /**
   * Adds a new wire connection to the repository.
   *
   * @param wireConnection the new wire connection
   * @return the id of the connection
   */
  String add(WireConnection wireConnection);

  /**
   * Gets a wire connection by its identifier, as provided by <code>
   * org.apache.tuweni.rlpx.wire.DefaultWireConnection#id</code>
   *
   * @param id the identifier of the wire connection
   * @return the wire connection associated with the identifier, or <code>null</code> if no such
   *     wire connection exists.
   */
  @Nullable
  WireConnection get(String id);

  /**
   * Provides a view of the wire connections as an iterable. There is no guarantee of sorting wire
   * connections.
   *
   * @return an Iterable object allowing to traverse all wire connections held by this repository
   */
  Iterable<WireConnection> asIterable();

  /**
   * Provides a subset of wire connections with a particular capabilities.
   *
   * @param identifier the subprotocol those connections must use
   * @return an Iterable object allowing to traverse all wire connections held by this repository
   */
  Iterable<WireConnection> asIterable(SubProtocolIdentifier identifier);

  /**
   * Closes the repository. After it has been closed, the repository should no longer be able to add
   * or retrieve connections.
   */
  void close();

  /**
   * Adds a listener called when connection occurs, ie when the connection is established and
   * capabilities are exchanged.
   *
   * @param listener the listener
   */
  void addConnectionListener(Listener listener);

  /**
   * Adds a listener called when disconnection occurs, either from us or the peer initiative.
   *
   * @param listener the listener
   */
  void addDisconnectionListener(Listener listener);
}
