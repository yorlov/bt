package bt.net;

import bt.metainfo.TorrentId;
import bt.protocol.Handshake;
import bt.protocol.IHandshakeFactory;
import bt.protocol.Message;
import bt.torrent.TorrentDescriptor;
import bt.torrent.TorrentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;

/**
 * Handles handshake exchange for incoming peer connections.
 *
 * @since 1.0
 */
class IncomingHandshakeHandler implements ConnectionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncomingHandshakeHandler.class);

    private IHandshakeFactory handshakeFactory;
    private TorrentRegistry torrentRegistry;
    private Collection<HandshakeHandler> handshakeHandlers;
    private Duration handshakeTimeout;

    public IncomingHandshakeHandler(IHandshakeFactory handshakeFactory, TorrentRegistry torrentRegistry,
                                    Collection<HandshakeHandler> handshakeHandlers, Duration handshakeTimeout) {
        this.handshakeFactory = handshakeFactory;
        this.torrentRegistry = torrentRegistry;
        this.handshakeHandlers = handshakeHandlers;
        this.handshakeTimeout = handshakeTimeout;
    }

    @Override
    public boolean handleConnection(PeerConnection connection) {
        Peer peer = connection.getRemotePeer();
        Message firstMessage = null;
        try {
            firstMessage = connection.readMessage(handshakeTimeout.toMillis());
        } catch (IOException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Failed to receive handshake from peer: {}. Reason: {} ({})",
                        peer, e.getClass().getName(), e.getMessage());
            }
        }

        if (firstMessage != null) {
            if (Handshake.class.equals(firstMessage.getClass())) {

                Handshake peerHandshake = (Handshake) firstMessage;
                TorrentId torrentId = peerHandshake.getTorrentId();
                Optional<TorrentDescriptor> descriptorOptional = torrentRegistry.getDescriptor(torrentId);
                // it's OK if descriptor is not present -- torrent might be being fetched at the time
                if (torrentRegistry.getTorrentIds().contains(torrentId)
                        && (!descriptorOptional.isPresent() || descriptorOptional.get().isActive())) {

                    Handshake handshake = handshakeFactory.createHandshake(torrentId);
                    handshakeHandlers.forEach(handler ->
                            handler.processOutgoingHandshake(handshake));

                    try {
                        connection.postMessage(handshake);
                    } catch (IOException e) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Failed to send handshake to peer: {}. Reason: {} ({})",
                                    peer, e.getClass().getName(), e.getMessage());
                        }
                        return false;
                    }
                    ((DefaultPeerConnection) connection).setTorrentId(torrentId);

                    handshakeHandlers.forEach(handler ->
                            handler.processIncomingHandshake(new WriteOnlyPeerConnection(connection), peerHandshake));

                    return true;
                }
            } else {
                LOGGER.warn("Received message of unexpected type '{}' instead of handshake from peer: {}",
                        firstMessage.getClass(), peer);
            }
        }
        return false;
    }
}
