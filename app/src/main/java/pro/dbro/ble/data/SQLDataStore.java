package pro.dbro.ble.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.Date;

import pro.dbro.ble.crypto.SodiumShaker;
import pro.dbro.ble.data.model.ChatContentProvider;
import pro.dbro.ble.data.model.DataUtil;
import pro.dbro.ble.data.model.Message;
import pro.dbro.ble.data.model.MessageCollection;
import pro.dbro.ble.data.model.MessageTable;
import pro.dbro.ble.data.model.Peer;
import pro.dbro.ble.data.model.PeerTable;
import pro.dbro.ble.protocol.Identity;
import pro.dbro.ble.protocol.OwnedIdentity;
import pro.dbro.ble.protocol.Protocol;

/**
 * API for the application's data persistence
 *
 * If the underlying data storage were to be replaced, this should be the
 * only class requiring modification.
 *
 * Created by davidbrodsky on 10/20/14.
 */
public class SQLDataStore extends DataStore {
    public static final String TAG = "DataManager";

    public SQLDataStore(Context context) {
        super(context);
    }

    @Nullable
    @Override
    public Peer createLocalPeerWithAlias(@NonNull String alias, @Nullable Protocol protocol) {
        OwnedIdentity localPeerIdentity = SodiumShaker.generateOwnedIdentityForAlias(alias);
        ContentValues dbEntry = new ContentValues();
        dbEntry.put(PeerTable.pubKey, localPeerIdentity.publicKey);
        dbEntry.put(PeerTable.secKey, localPeerIdentity.secretKey);
        dbEntry.put(PeerTable.alias, alias);
        dbEntry.put(PeerTable.lastSeenDate, DataUtil.storedDateFormatter.format(new Date()));
        if (protocol != null) {
            // If protocol is available, use it to cache the Identity packet for transmission
            dbEntry.put(PeerTable.rawPkt, protocol.createIdentityResponse(localPeerIdentity));
        }
        Uri newIdentityUri = mContext.getContentResolver().insert(ChatContentProvider.Peers.PEERS, dbEntry);
        return getPeerById(Integer.parseInt(newIdentityUri.getLastPathSegment()));
    }

    /**
     * @return the first user peer entry in the database,
     * or null if no identity is set.
     */
    @Override
    @Nullable
    public Peer getPrimaryLocalPeer() {
        // TODO: caching
        Cursor result = mContext.getContentResolver().query(ChatContentProvider.Peers.PEERS,
                null,
                PeerTable.secKey + " IS NOT NULL",
                null,
                null);
        if (result != null && result.moveToFirst()) {
            return new Peer(result);
        }
        return null;
    }

    @Nullable
    @Override
    public MessageCollection getOutgoingMessagesForPeer(@NonNull Peer recipient) {
        // TODO: filtering. Don't return Cursor
        Cursor messagesCursor = mContext.getContentResolver().query(ChatContentProvider.Messages.MESSAGES, null, null, null, null);
        return new MessageCollection(messagesCursor);
    }

    @Nullable
    @Override
    public Peer createOrUpdateRemotePeerWithProtocolIdentity(@NonNull Identity remoteIdentity) {
        // Query if peer exists
        Peer peer = getPeerByPubKey(remoteIdentity.publicKey);

        ContentValues peerValues = new ContentValues();
        peerValues.put(PeerTable.lastSeenDate, DataUtil.storedDateFormatter.format(new Date()));
        peerValues.put(PeerTable.pubKey, remoteIdentity.publicKey);
        peerValues.put(PeerTable.alias, remoteIdentity.alias);
        peerValues.put(PeerTable.rawPkt, remoteIdentity.rawPacket);

        if (peer != null) {
            // Peer exists. Modify lastSeenDate
            int updated = mContext.getContentResolver().update(
                    ChatContentProvider.Peers.PEERS,
                    peerValues,
                    "quote("+ PeerTable.pubKey + ") = ?" ,
                    new String[] {DataUtil.bytesToHex(remoteIdentity.publicKey)});
            if (updated != 1) {
                Log.e(TAG, "Failed to update peer last seen");
            }
        } else {
            // Peer does not exist. Create.
            Uri peerUri = mContext.getContentResolver().insert(
                    ChatContentProvider.Peers.PEERS,
                    peerValues);

            // Fetch newly created peer
            peer = getPeerById(Integer.parseInt(peerUri.getLastPathSegment()));

            if (peer == null) {
                Log.e(TAG, "Failed to query peer after insertion.");
            }
        }
        return peer;
    }

    @Nullable
    @Override
    public Message createOrUpdateMessageWithProtocolMessage(@NonNull pro.dbro.ble.protocol.Message protocolMessage) {
        // Query if peer exists
        Peer peer = createOrUpdateRemotePeerWithProtocolIdentity(protocolMessage.sender);

        if (peer == null)
            throw new IllegalStateException("Failed to get peer for message");

        // See if message exists
        Message message = getMessageBySignature(protocolMessage.signature);
        if (message == null) {
            // Message doesn't exist in our database

            // Insert message into database
            ContentValues newMessageEntry = new ContentValues();
            newMessageEntry.put(MessageTable.body, protocolMessage.body);
            newMessageEntry.put(MessageTable.peerId, peer.getId());
            newMessageEntry.put(MessageTable.receivedDate, DataUtil.storedDateFormatter.format(new Date()));
            newMessageEntry.put(MessageTable.authoredDate, DataUtil.storedDateFormatter.format(protocolMessage.authoredDate));
            newMessageEntry.put(MessageTable.signature, protocolMessage.signature);
            newMessageEntry.put(MessageTable.replySig, protocolMessage.replySig);
            newMessageEntry.put(MessageTable.rawPacket, protocolMessage.rawPacket);

            Uri newMessageUri = mContext.getContentResolver().insert(
                    ChatContentProvider.Messages.MESSAGES,
                    newMessageEntry);
            message = getMessageById(Integer.parseInt(newMessageUri.getLastPathSegment()));
        } else {
            // We already have a message with this signature
            // Since we currently don't have any mutable message fields (e.g hopcount)
            // do nothing
            Log.i(TAG, "Received stored message. Ignoring");
        }
        return message;
    }

    @Nullable
    @Override
    public Message getMessageBySignature(@NonNull byte[] signature) {
        Cursor messageCursor = mContext.getContentResolver().query(
                ChatContentProvider.Messages.MESSAGES,
                null,
                "quote(" + MessageTable.signature + ") = ?",
                new String[] {DataUtil.bytesToHex(signature)},
                null);
        if (messageCursor != null && messageCursor.moveToFirst()) {
            return new Message(messageCursor);
        }
        return null;
    }

    @Nullable
    @Override
    public Message getMessageById(int id) {
        Cursor messageCursor = mContext.getContentResolver().query(ChatContentProvider.Messages.MESSAGES, null,
                MessageTable.id + " = ?",
                new String[]{String.valueOf(id)},
                null);
        if (messageCursor != null && messageCursor.moveToFirst()) {
            return new Message(messageCursor);
        }
        return null;
    }

    @Nullable
    @Override
    public Peer getPeerByPubKey(@NonNull byte[] publicKey) {
        Cursor peerCursor = mContext.getContentResolver().query(
                ChatContentProvider.Peers.PEERS,
                null,
                "quote(" + PeerTable.pubKey + ") = ?",
                new String[] {DataUtil.bytesToHex(publicKey)},
                null);
        if (peerCursor != null && peerCursor.moveToFirst()) {
            return new Peer(peerCursor);
        }
        return null;
    }

    @Nullable
    @Override
    public Peer getPeerById(int id) {
        Cursor peerCursor = mContext.getContentResolver().query(
                ChatContentProvider.Peers.PEERS,
                null,
                PeerTable.id + " = ?",
                new String[] {String.valueOf(id)},
                null);
        if (peerCursor != null && peerCursor.moveToFirst()) {
            return new Peer(peerCursor);
        }
        return null;
    }
}
