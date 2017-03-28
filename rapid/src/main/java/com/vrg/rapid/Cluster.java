/*
 * Copyright © 2016 - 2017 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the “License”); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an “AS IS” BASIS, without warranties or conditions of any kind,
 * EITHER EXPRESS OR IMPLIED. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.vrg.rapid;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.vrg.rapid.monitoring.ILinkFailureDetector;
import com.vrg.rapid.monitoring.PingPongFailureDetector;
import com.vrg.rapid.pb.JoinMessage;
import com.vrg.rapid.pb.JoinResponse;
import com.vrg.rapid.pb.JoinStatusCode;
import com.vrg.rapid.pb.Response;
import io.grpc.ExperimentalApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The public API for Rapid.
 */
public final class Cluster {
    private static final Logger LOG = LoggerFactory.getLogger("Cluster");
    private static final int K = 10;
    private static final int H = 8;
    private static final int L = 1;
    private static final int RETRIES = 5;
    private final MembershipService membershipService;
    private final RpcServer rpcServer;

    private Cluster(final RpcServer rpcServer, final MembershipService membershipService) {
        this.membershipService = membershipService;
        this.rpcServer = rpcServer;
    }

    public static class Builder {
        @Nullable private ILinkFailureDetector linkFailureDetector = null;
        private boolean logProposals = false;
        private Map<String, String> metadata = Collections.emptyMap();
        private final HostAndPort listenAddress;

        /**
         * Instantiates a builder for a Rapid Cluster node that will listen on the given {@code listenAddress}
         *
         * @param listenAddress The listen address of the node being instantiated
         */
        public Builder(final HostAndPort listenAddress) {
            this.listenAddress = listenAddress;
        }

        /**
         * Maintain a log of proposals inside Rapid.
         *
         * @param logProposals Will log proposals if true.
         */
        @ExperimentalApi
        public Builder setLogProposals(final boolean logProposals) {
            this.logProposals = logProposals;
            return this;
        }

        /**
         * Set static application-specific metadata that is associated with the node being instantiated.
         * This may include tags like "role":"frontend".
         *
         * @param metadata A map specifying a set of key-value tags.
         */
        public Builder setMetadata(final Map<String, String> metadata) {
            Objects.requireNonNull(metadata);
            this.metadata = metadata;
            return this;
        }

        /**
         * Set a link failure detector to use for monitors to watch their monitorees.
         *
         * @param linkFailureDetector A link failure detector used as input for Rapid's failure detection.
         */
        @ExperimentalApi
        public Builder setLinkFailureDetector(final ILinkFailureDetector linkFailureDetector) {
            Objects.requireNonNull(linkFailureDetector);
            this.linkFailureDetector = linkFailureDetector;
            return this;
        }

        /**
         * Joins an existing cluster, using {@code seedAddress} to bootstrap.
         *
         * @param seedAddress Seed node for the bootstrap protocol
         * @throws IOException Thrown if we cannot successfully start a server
         */
        public Cluster join(final HostAndPort seedAddress) throws IOException, InterruptedException {
            if (linkFailureDetector == null) {
                linkFailureDetector = new PingPongFailureDetector(listenAddress);
            }
            return joinCluster(seedAddress, this.listenAddress, this.logProposals,
                               this.linkFailureDetector, this.metadata);
        }

        /**
         * Start a cluster without joining. Required to bootstrap a seed node.
         *
         * @throws IOException Thrown if we cannot successfully start a server
         */
        public Cluster start() throws IOException {
            if (linkFailureDetector == null) {
                linkFailureDetector = new PingPongFailureDetector(listenAddress);
            }
            return startCluster(this.listenAddress, this.logProposals, this.linkFailureDetector, this.metadata);
        }
    }

    static Cluster joinCluster(final HostAndPort seedAddress,
                               final HostAndPort listenAddress,
                               final boolean logProposals,
                               final ILinkFailureDetector linkFailureDetector,
                               final Map<String, String> metadata) throws IOException, InterruptedException {
        UUID currentIdentifier = UUID.randomUUID();

        final RpcServer server = new RpcServer(listenAddress);
        final RpcClient joinerClient = new RpcClient(listenAddress);
        server.startServer();
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            joinerClient.createLongLivedConnections(ImmutableSet.of(seedAddress));
            // First, get the configuration ID and the monitors to contact from the seed node.
            final JoinResponse joinPhaseOneResult;
            try {
                joinPhaseOneResult = joinerClient.sendJoinMessage(seedAddress,
                        listenAddress, currentIdentifier).get();
            } catch (final ExecutionException e) {
                LOG.error("Join message to seed {} returned an exception: {}", seedAddress, e.getCause());
                continue;
            }
            assert joinPhaseOneResult != null;

            switch (joinPhaseOneResult.getStatusCode()) {
                case CONFIG_CHANGED:
                case UUID_ALREADY_IN_RING:
                    currentIdentifier = UUID.randomUUID();
                    continue;
                case HOSTNAME_ALREADY_IN_RING:
                    /*
                     * TODO: This special case needs handling. If the joinPhase2 request times out,
                     * a client may re-try a join by contacting the seed and get this response. It should
                     * ideally get the configuration streamed to it.
                     */
                    LOG.error("Hostname already in configuration {}", joinPhaseOneResult.getConfigurationId());
                    continue;
                case MEMBERSHIP_REJECTED:
                    LOG.error("Membership rejected by {}. Quitting.", joinPhaseOneResult.getSender());
                    throw new RuntimeException("Membership rejected");
                case SAFE_TO_JOIN:
                    break;
                default:
                    throw new RuntimeException("Unrecognized status code");
            }

            if (attempt > 0) {
                LOG.info("{} is retrying a join under a new configuration {}",
                        listenAddress, joinPhaseOneResult.getConfigurationId());
            }

            // We have the list of monitors. Now contact them as part of phase 2.
            final List<HostAndPort> monitorList = joinPhaseOneResult.getHostsList().stream()
                    .map(HostAndPort::fromString)
                    .collect(Collectors.toList());

            int ringNumber = 0;
            final JoinMessage.Builder builder = JoinMessage.newBuilder()
                                                        .setSender(listenAddress.toString())
                                                        .setUuid(currentIdentifier.toString())
                                                        .putAllMetadata(metadata)
                                                        .setConfigurationId(joinPhaseOneResult.getConfigurationId());
            final List<ListenableFuture<Response>> responseFutures = new ArrayList<>();
            final SettableFuture<Cluster> returnValue = SettableFuture.create();
            final Object lock = new Object();
            for (final HostAndPort monitor : monitorList) {
                final JoinMessage msg = builder.setRingNumber(ringNumber).build();
                final SettableFuture<JoinResponse> joinListener = SettableFuture.create();
                Futures.addCallback(joinListener, new FutureCallback<JoinResponse>() {
                    @Override
                    public void onSuccess(@Nullable final JoinResponse response) {
                        synchronized (lock) {
                            if (response == null) {
                                LOG.info("Received a null response.");
                                return;
                            }
                            if (response.getStatusCode() == JoinStatusCode.MEMBERSHIP_REJECTED) {
                                LOG.info("Membership rejected by {}. Quitting.", response.getSender());
                                return;
                            }

                            if (response.getStatusCode() == JoinStatusCode.SAFE_TO_JOIN
                                    && response.getConfigurationId() != joinPhaseOneResult.getConfigurationId()) {
                                // Safe to proceed. Extract the list of hosts and identifiers from the message,
                                // assemble a MembershipService object and start an RpcServer.
                                final List<HostAndPort> allHosts = response.getHostsList().stream()
                                        .map(HostAndPort::fromString)
                                        .collect(Collectors.toList());
                                final List<UUID> identifiersSeen = response.getIdentifiersList().stream()
                                        .map(UUID::fromString)
                                        .collect(Collectors.toList());

                                assert identifiersSeen.size() > 0;
                                assert allHosts.size() > 0;
                                final MembershipView membershipViewFinal =
                                        new MembershipView(K, identifiersSeen, allHosts);
                                final WatermarkBuffer watermarkBuffer = new WatermarkBuffer(K, H, L);
                                final MembershipService membershipService = new MembershipService.Builder(listenAddress,
                                        watermarkBuffer,
                                        membershipViewFinal)
                                        .setLogProposals(logProposals)
                                        .setLinkFailureDetector(linkFailureDetector)
                                        .build();
                                server.setMembershipService(membershipService);
                                returnValue.set(new Cluster(server, membershipService));
                            }
                        }
                    }

                    @Override
                    public void onFailure(final Throwable throwable) {
                        throwable.printStackTrace();
                    }
                });
                server.setJoinResponseListener(monitor, joinListener);
                final ListenableFuture<Response> call = joinerClient.sendJoinPhase2Message(monitor, msg);
                responseFutures.add(call);
                ringNumber++;
            }

            // The returned list of responses must contain the full configuration (hosts and identifiers) we just
            // joined. Else, there's an error and we throw an exception.
            try {
                // TODO: This is only correct if we use consensus for node addition.
                // Unsuccessful responses will be null.
                Futures.successfulAsList(responseFutures).get();
                return returnValue.get();
            } catch (final ExecutionException e) {
                LOG.error("JoinPhaseTwo request by {} for configuration {} threw an exception. Retrying. {}",
                        listenAddress, joinPhaseOneResult.getConfigurationId(), e.getMessage());
            }
        }
        server.stopServer();
        throw new RuntimeException("Join attempt unsuccessful " + listenAddress);
    }

    /**
     * Start a cluster without joining. Required to bootstrap a seed node.
     *
     * @param listenAddress Address to bind to after successful bootstrap
     * @param logProposals maintain a log of announced view change proposals
     * @param linkFailureDetector a list of checks to perform before a monitor processes a join phase two message
     * @throws IOException Thrown if we cannot successfully start a server
     */
    @VisibleForTesting
    static Cluster startCluster(final HostAndPort listenAddress,
                                final boolean logProposals,
                                final ILinkFailureDetector linkFailureDetector,
                                final Map<String, String> metadata) throws IOException {
        Objects.requireNonNull(listenAddress);
        final RpcServer rpcServer = new RpcServer(listenAddress);
        final UUID currentIdentifier = UUID.randomUUID();
        final MembershipView membershipView = new MembershipView(K, Collections.singletonList(currentIdentifier),
                Collections.singletonList(listenAddress));
        final WatermarkBuffer watermarkBuffer = new WatermarkBuffer(K, H, L);
        final MembershipService membershipService = new MembershipService.Builder(listenAddress, watermarkBuffer,
                membershipView)
                .setLogProposals(logProposals)
                .setLinkFailureDetector(linkFailureDetector)
                .setRole(metadata)
                .build();
        rpcServer.setMembershipService(membershipService);
        rpcServer.startServer();
        return new Cluster(rpcServer, membershipService);
    }

    /**
     * Returns the list of hosts currently in the membership set.
     *
     * @return list of hosts in the membership set
     */
    public List<HostAndPort> getMemberlist() {
        return membershipService.getMembershipView();
    }

    /**
     * Register callbacks for cluster events.
     *
     * @param event Cluster event to subscribe to
     * @param callback Callback to be executed when {@code event} occurs.
     */
    public void registerSubscription(final ClusterEvents event,
                                     final Consumer<List<NodeStatusChange>> callback) {
        membershipService.registerSubscription(event, callback);
    }

    /**
     * Shutdown the RpcServer
     */
    public void shutdown() throws InterruptedException {
        // TODO: this should probably be a "leave" method
        LOG.debug("Shutting down RpcServer and MembershipService");
        rpcServer.stopServer();
        membershipService.shutdown();
    }
}