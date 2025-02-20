package io.cloudstate.javasupport.crdt;

import io.cloudstate.javasupport.ClientActionContext;
import io.cloudstate.javasupport.EffectContext;

import java.util.Optional;

/**
 * Context for handling a command.
 * <p>
 * This may be passed to any {@link CommandHandler} annotated element.
 */
public interface CommandContext extends CrdtContext, CrdtFactory, EffectContext, ClientActionContext {
    /**
     * The id of the command. This is an internal ID generated by the proxy, and is unique to a given entity stream.
     * It may be used for debugging purposes.
     *
     * @return The ID of the command.
     */
    long commandId();

    /**
     * The name of the command.
     * <p>
     * Corresponds to the name of the rpc call in the protobuf definition.
     *
     * @return The name of the command.
     */
    String commandName();

    /**
     * Delete the CRDT.
     * <p>
     * When a CRDT is deleted, it may not be created again. Additionally, CRDT deletion results in tombstones that
     * get accumulated for the life of the cluster. If you expect to delete CRDTs frequently, it's recommended that you
     * store them in a single or sharded {@link ORMap}, rather than individual CRDTs.
     */
    void delete();
}
