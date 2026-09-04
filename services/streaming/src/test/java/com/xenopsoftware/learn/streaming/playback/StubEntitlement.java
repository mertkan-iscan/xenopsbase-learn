package com.xenopsoftware.learn.streaming.playback;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catalog, as far as these tests need one (T-5.1/T-5.3/T-5.5 will be the real thing).
 *
 * <p>A stub rather than the production {@link UnassignedContent}, because a fail-closed default
 * can prove that the refusal happens and nothing else: with it, the assignment check, the gate
 * check and every check after them are unreachable and untested. The stub is what lets each of
 * T-3.4's links be shown to be load-bearing on its own.
 */
public class StubEntitlement implements ContentEntitlement {

    private final Map<UUID, NodeEntitlement> nodes = new ConcurrentHashMap<>();

    public void put(NodeEntitlement node) {
        nodes.put(node.nodeId(), node);
    }

    public void clear() {
        nodes.clear();
    }

    @Override
    public Optional<NodeEntitlement> lookUp(UUID nodeId, Viewer viewer) {
        return Optional.ofNullable(nodes.get(nodeId));
    }
}
