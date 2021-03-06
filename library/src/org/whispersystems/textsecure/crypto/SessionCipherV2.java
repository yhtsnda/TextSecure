package org.whispersystems.textsecure.crypto;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import org.whispersystems.textsecure.crypto.ecc.Curve;
import org.whispersystems.textsecure.crypto.ecc.ECKeyPair;
import org.whispersystems.textsecure.crypto.ecc.ECPublicKey;
import org.whispersystems.textsecure.crypto.protocol.CiphertextMessage;
import org.whispersystems.textsecure.crypto.protocol.PreKeyWhisperMessage;
import org.whispersystems.textsecure.crypto.protocol.WhisperMessageV2;
import org.whispersystems.textsecure.crypto.ratchet.ChainKey;
import org.whispersystems.textsecure.crypto.ratchet.MessageKeys;
import org.whispersystems.textsecure.crypto.ratchet.RootKey;
import org.whispersystems.textsecure.storage.RecipientDevice;
import org.whispersystems.textsecure.storage.SessionRecordV2;
import org.whispersystems.textsecure.storage.SessionState;
import org.whispersystems.textsecure.util.Conversions;

import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SessionCipherV2 extends SessionCipher {

  private final Context         context;
  private final MasterSecret    masterSecret;
  private final RecipientDevice recipient;

  public SessionCipherV2(Context context,
                         MasterSecret masterSecret,
                         RecipientDevice recipient)
  {
    this.context      = context;
    this.masterSecret = masterSecret;
    this.recipient    = recipient;
  }

  @Override
  public CiphertextMessage encrypt(byte[] paddedMessage) {
    synchronized (SESSION_LOCK) {
      SessionRecordV2 sessionRecord   = getSessionRecord();
      SessionState    sessionState    = sessionRecord.getSessionState();
      ChainKey        chainKey        = sessionState.getSenderChainKey();
      MessageKeys     messageKeys     = chainKey.getMessageKeys();
      ECPublicKey     senderEphemeral = sessionState.getSenderEphemeral();
      int             previousCounter = sessionState.getPreviousCounter();

      byte[]            ciphertextBody    = getCiphertext(messageKeys, paddedMessage);
      CiphertextMessage ciphertextMessage = new WhisperMessageV2(messageKeys.getMacKey(),
                                                                 senderEphemeral, chainKey.getIndex(),
                                                                 previousCounter, ciphertextBody);

      if (sessionState.hasPendingPreKey()) {
        Pair<Integer, ECPublicKey> pendingPreKey       = sessionState.getPendingPreKey();
        int                        localRegistrationId = sessionState.getLocalRegistrationId();

        ciphertextMessage = new PreKeyWhisperMessage(localRegistrationId, pendingPreKey.first,
                                                     pendingPreKey.second,
                                                     sessionState.getLocalIdentityKey(),
                                                     (WhisperMessageV2) ciphertextMessage);
      }

      sessionState.setSenderChainKey(chainKey.getNextChainKey());
      sessionRecord.save();

      return ciphertextMessage;
    }
  }

  @Override
  public byte[] decrypt(byte[] decodedMessage)
      throws InvalidMessageException, DuplicateMessageException
  {
    synchronized (SESSION_LOCK) {
      SessionRecordV2    sessionRecord  = getSessionRecord();
      SessionState       sessionState   = sessionRecord.getSessionState();
      List<SessionState> previousStates = sessionRecord.getPreviousSessions();

      try {
        byte[] plaintext = decrypt(sessionState, decodedMessage);
        sessionRecord.save();

        return plaintext;
      } catch (InvalidMessageException e) {
        Log.w("SessionCipherV2", e);
      }

      for (SessionState previousState : previousStates) {
        try {
          Log.w("SessionCipherV2", "Attempting decrypt on previous state...");
          byte[] plaintext = decrypt(previousState, decodedMessage);
          sessionRecord.save();

          return plaintext;
        } catch (InvalidMessageException e) {
          Log.w("SessionCipherV2", e);
        }
      }

      throw new InvalidMessageException("No valid sessions.");
    }
  }

  public byte[] decrypt(SessionState sessionState, byte[] decodedMessage)
      throws InvalidMessageException, DuplicateMessageException
  {
    if (!sessionState.hasSenderChain()) {
      throw new InvalidMessageException("Uninitialized session!");
    }

    WhisperMessageV2 ciphertextMessage = new WhisperMessageV2(decodedMessage);
    ECPublicKey      theirEphemeral    = ciphertextMessage.getSenderEphemeral();
    int              counter           = ciphertextMessage.getCounter();
    ChainKey         chainKey          = getOrCreateChainKey(sessionState, theirEphemeral);
    MessageKeys      messageKeys       = getOrCreateMessageKeys(sessionState, theirEphemeral,
                                                                chainKey, counter);

    ciphertextMessage.verifyMac(messageKeys.getMacKey());

    byte[] plaintext = getPlaintext(messageKeys, ciphertextMessage.getBody());

    sessionState.clearPendingPreKey();

    return plaintext;

  }

  @Override
  public int getRemoteRegistrationId() {
    synchronized (SESSION_LOCK) {
      SessionRecordV2 sessionRecord = getSessionRecord();
      return sessionRecord.getSessionState().getRemoteRegistrationId();
    }
  }

  private ChainKey getOrCreateChainKey(SessionState sessionState, ECPublicKey theirEphemeral)
      throws InvalidMessageException
  {
    try {
      if (sessionState.hasReceiverChain(theirEphemeral)) {
        return sessionState.getReceiverChainKey(theirEphemeral);
      } else {
        RootKey                 rootKey         = sessionState.getRootKey();
        ECKeyPair               ourEphemeral    = sessionState.getSenderEphemeralPair();
        Pair<RootKey, ChainKey> receiverChain   = rootKey.createChain(theirEphemeral, ourEphemeral);
        ECKeyPair               ourNewEphemeral = Curve.generateKeyPairForType(Curve.DJB_TYPE, true);
        Pair<RootKey, ChainKey> senderChain     = receiverChain.first.createChain(theirEphemeral, ourNewEphemeral);

        sessionState.setRootKey(senderChain.first);
        sessionState.addReceiverChain(theirEphemeral, receiverChain.second);
        sessionState.setPreviousCounter(sessionState.getSenderChainKey().getIndex()-1);
        sessionState.setSenderChain(ourNewEphemeral, senderChain.second);

        return receiverChain.second;
      }
    } catch (InvalidKeyException e) {
      throw new InvalidMessageException(e);
    }
  }

  private MessageKeys getOrCreateMessageKeys(SessionState sessionState,
                                             ECPublicKey theirEphemeral,
                                             ChainKey chainKey, int counter)
      throws InvalidMessageException, DuplicateMessageException
  {
    if (chainKey.getIndex() > counter) {
      if (sessionState.hasMessageKeys(theirEphemeral, counter)) {
        return sessionState.removeMessageKeys(theirEphemeral, counter);
      } else {
        throw new DuplicateMessageException("Received message with old counter: " +
                                                chainKey.getIndex() + " , " + counter);
      }
    }

    if (chainKey.getIndex() - counter > 2000) {
      throw new InvalidMessageException("Over 2000 messages into the future!");
    }

    while (chainKey.getIndex() < counter) {
      MessageKeys messageKeys = chainKey.getMessageKeys();
      sessionState.setMessageKeys(theirEphemeral, messageKeys);
      chainKey = chainKey.getNextChainKey();
    }

    sessionState.setReceiverChainKey(theirEphemeral, chainKey.getNextChainKey());
    return chainKey.getMessageKeys();
  }

  private byte[] getCiphertext(MessageKeys messageKeys, byte[] plaintext) {
    try {
      Cipher cipher = getCipher(Cipher.ENCRYPT_MODE,
                                messageKeys.getCipherKey(),
                                messageKeys.getCounter());

      return cipher.doFinal(plaintext);
    } catch (IllegalBlockSizeException e) {
      throw new AssertionError(e);
    } catch (BadPaddingException e) {
      throw new AssertionError(e);
    }
  }

  private byte[] getPlaintext(MessageKeys messageKeys, byte[] cipherText) {
    try {
      Cipher cipher = getCipher(Cipher.DECRYPT_MODE,
                                messageKeys.getCipherKey(),
                                messageKeys.getCounter());
      return cipher.doFinal(cipherText);
    } catch (IllegalBlockSizeException e) {
      throw new AssertionError(e);
    } catch (BadPaddingException e) {
      throw new AssertionError(e);
    }
  }

  private Cipher getCipher(int mode, SecretKeySpec key, int counter)  {
    try {
      Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");

      byte[] ivBytes = new byte[16];
      Conversions.intToByteArray(ivBytes, 0, counter);

      IvParameterSpec iv = new IvParameterSpec(ivBytes);
      cipher.init(mode, key, iv);

      return cipher;
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    } catch (NoSuchPaddingException e) {
      throw new AssertionError(e);
    } catch (java.security.InvalidKeyException e) {
      throw new AssertionError(e);
    } catch (InvalidAlgorithmParameterException e) {
      throw new AssertionError(e);
    }
  }


  private SessionRecordV2 getSessionRecord() {
    return new SessionRecordV2(context, masterSecret, recipient);
  }

}
