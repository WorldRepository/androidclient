/*
 * Kontalk Android client
 * Copyright (C) 2015 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.crypto;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Base64InputStream;
import android.util.Base64OutputStream;
import android.util.Log;

import org.kontalk.Kontalk;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.crypto.PGP.PGPDecryptedKeyPairRing;
import org.kontalk.crypto.PGP.PGPKeyPairRing;
import org.kontalk.util.MessageUtils;

import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKeyPair;
import org.spongycastle.openpgp.PGPObjectFactory;
import org.spongycastle.openpgp.PGPPrivateKey;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPPublicKeyRing;
import org.spongycastle.openpgp.PGPSecretKey;
import org.spongycastle.openpgp.PGPSecretKeyRing;
import org.spongycastle.openpgp.operator.KeyFingerPrintCalculator;
import org.spongycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.spongycastle.openpgp.operator.PGPDigestCalculatorProvider;
import org.spongycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.spongycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.spongycastle.operator.OperatorCreationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Iterator;


/** Personal asymmetric encryption key. */
public class PersonalKey implements Parcelable {
    private static final String TAG = Kontalk.TAG;

    public static final int MIN_PASSPHRASE_LENGTH = 4;

    /** Decrypted key pair (for direct usage). */
    private final PGPDecryptedKeyPairRing mPair;
    /** X.509 bridge certificate. */
    private final X509Certificate mBridgeCert;

    private PersonalKey(PGPDecryptedKeyPairRing keyPair, X509Certificate bridgeCert) {
        mPair = keyPair;
        mBridgeCert = bridgeCert;
    }

    private PersonalKey(PGPKeyPair signKp, PGPKeyPair encryptKp, X509Certificate bridgeCert) {
        this(new PGPDecryptedKeyPairRing(signKp, encryptKp), bridgeCert);
    }

    private PersonalKey(Parcel in) throws PGPException, IOException {
        mPair = PGP.fromParcel(in);
        mBridgeCert = null;
        // TODO mBridgeCert = X509Bridge.fromParcel(in);
    }

    public PGPKeyPair getEncryptKeyPair() {
        return mPair.encryptKey;
    }

    public PGPKeyPair getSignKeyPair() {
        return mPair.signKey;
    }

    public X509Certificate getBridgeCertificate() {
        return mBridgeCert;
    }

    public PrivateKey getBridgePrivateKey() throws PGPException {
        return PGP.convertPrivateKey(mPair.signKey.getPrivateKey());
    }

    public PGPPublicKeyRing getPublicKeyRing() throws IOException {
        return new PGPPublicKeyRing(getEncodedPublicKeyRing(), new BcKeyFingerprintCalculator());
    }

    public byte[] getEncodedPublicKeyRing() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mPair.signKey.getPublicKey().encode(out);
        mPair.encryptKey.getPublicKey().encode(out);
        return out.toByteArray();
    }

    /** Returns the first user ID on the key that matches the given network. */
    public String getUserId(String network) {
        return PGP.getUserId(mPair.signKey.getPublicKey(), network);
    }

    public String getFingerprint() {
        return MessageUtils.bytesToHex(mPair.signKey.getPublicKey().getFingerprint());
    }

    public PGPKeyPairRing storeNetwork(String userId, String network, String name, String passphrase) throws PGPException {
        return store(name, userId + '@' + network, null, passphrase);
    }

    public PGPKeyPairRing store(String name, String email, String comment, String passphrase) throws PGPException {
        // name[ (comment)] <[email]>
        StringBuilder userid = new StringBuilder(name);

        if (comment != null) userid
            .append(" (")
            .append(comment)
            .append(')');

        userid.append(" <");
        if (email != null)
            userid.append(email);
        userid.append('>');

        PGPKeyPairRing kp = PGP.store(mPair, userid.toString(), passphrase);

        return kp;
    }

    /**
     * Updates the public key.
     * @return the public keyring.
     */
    public PGPPublicKeyRing update(byte[] keyData) throws IOException {
        PGPPublicKeyRing ring = new PGPPublicKeyRing(keyData, new BcKeyFingerprintCalculator());
        // FIXME should loop through the ring and check for master/subkey
        mPair.signKey = new PGPKeyPair(ring.getPublicKey(), mPair.signKey.getPrivateKey());
        return ring;
    }

    public PersonalKey copy(X509Certificate bridgeCert) {
        return new PersonalKey(mPair, bridgeCert);
    }

    public String toBase64() {
        ObjectOutputStream os = null;
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Base64OutputStream enc = new Base64OutputStream(buf, Base64.NO_WRAP);
            os = new ObjectOutputStream(enc);

            PGP.serialize(mPair, os);

            os.close();
            return buf.toString();
        }
        catch (Exception e) {
            // shouldn't happen - crash
            throw new RuntimeException(e);
        }
        finally {
            try {
                if (os != null)
                    os.close();
            }
            catch (IOException ignored) {
            }
        }
    }

    public static PersonalKey fromBase64(String data) {
        ObjectInputStream is = null;
        try {
            ByteArrayInputStream buf = new ByteArrayInputStream(data.getBytes());
            Base64InputStream dec = new Base64InputStream(buf, Base64.NO_WRAP);
            is = new ObjectInputStream(dec);

            PGPDecryptedKeyPairRing pair = PGP.unserialize(is);

            dec.close();
            return new PersonalKey(pair, null);
        }
        catch (Exception e) {
            // shouldn't happen - crash
            throw new RuntimeException(e);
        }
        finally {
            try {
                if (is != null)
                    is.close();
            }
            catch (IOException ignored) {
            }
        }
    }

    /** Checks that the given personal key data is correct. */
    public static PGPKeyPairRing test(InputStream privateKeyData, InputStream publicKeyData, String passphrase, InputStream bridgeCertData)
            throws PGPException, IOException, CertificateException, NoSuchProviderException {

        KeyFingerPrintCalculator fpr = new BcKeyFingerprintCalculator();
        PGPSecretKeyRing secRing = new PGPSecretKeyRing(privateKeyData, fpr);
        PGPPublicKeyRing pubRing = new PGPPublicKeyRing(publicKeyData, fpr);

        // X.509 bridge certificate
        X509Certificate bridgeCert = X509Bridge.load(bridgeCertData);

        return test(secRing, pubRing, passphrase, bridgeCert);
    }

    /** Checks that the given personal key data is correct. */
    public static PGPKeyPairRing test(byte[] privateKeyData, byte[] publicKeyData, String passphrase, byte[] bridgeCertData)
            throws PGPException, IOException, CertificateException, NoSuchProviderException {

        KeyFingerPrintCalculator fpr = new BcKeyFingerprintCalculator();
        PGPSecretKeyRing secRing = new PGPSecretKeyRing(privateKeyData, fpr);
        PGPPublicKeyRing pubRing = new PGPPublicKeyRing(publicKeyData, fpr);

        // X.509 bridge certificate
        X509Certificate bridgeCert = (bridgeCertData != null) ?
            X509Bridge.load(bridgeCertData) : null;

        return test(secRing, pubRing, passphrase, bridgeCert);
    }

    private static PGPKeyPairRing test(PGPSecretKeyRing secRing, PGPPublicKeyRing pubRing, String passphrase, X509Certificate bridgeCert)
            throws PGPException, IOException, CertificateException, NoSuchProviderException {

        // for now we just do a test load
        load(secRing, pubRing, passphrase, bridgeCert);

        return new PGPKeyPairRing(pubRing, secRing);
    }

    /** Creates a {@link PersonalKey} from private and public key input streams. */
    public static PersonalKey load(InputStream privateKeyData, InputStream publicKeyData, String passphrase, InputStream bridgeCertData)
            throws PGPException, IOException, CertificateException, NoSuchProviderException {

        KeyFingerPrintCalculator fpr = new BcKeyFingerprintCalculator();
        PGPSecretKeyRing secRing = new PGPSecretKeyRing(privateKeyData, fpr);
        PGPPublicKeyRing pubRing = new PGPPublicKeyRing(publicKeyData, fpr);

        // X.509 bridge certificate
        X509Certificate bridgeCert = (bridgeCertData != null) ?
            X509Bridge.load(bridgeCertData) : null;

        return load(secRing, pubRing, passphrase, bridgeCert);
    }

    /** Creates a {@link PersonalKey} from private and public key byte buffers. */
    public static PersonalKey load(byte[] privateKeyData, byte[] publicKeyData, String passphrase, byte[] bridgeCertData)
            throws PGPException, IOException, CertificateException, NoSuchProviderException {

        KeyFingerPrintCalculator fpr = new BcKeyFingerprintCalculator();
        PGPSecretKeyRing secRing = new PGPSecretKeyRing(privateKeyData, fpr);
        PGPPublicKeyRing pubRing = new PGPPublicKeyRing(publicKeyData, fpr);

        // X.509 bridge certificate
        X509Certificate bridgeCert = (bridgeCertData != null) ?
            X509Bridge.load(bridgeCertData) : null;

        return load(secRing, pubRing, passphrase, bridgeCert);
    }

    @SuppressWarnings("unchecked")
    public static PersonalKey load(PGPSecretKeyRing secRing, PGPPublicKeyRing pubRing, String passphrase, X509Certificate bridgeCert)
            throws PGPException, IOException, CertificateException, NoSuchProviderException {

        PGPDigestCalculatorProvider sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build();
        PBESecretKeyDecryptor decryptor = new JcePBESecretKeyDecryptorBuilder(sha1Calc)
            .setProvider(PGP.PROVIDER)
            .build(passphrase.toCharArray());

        PGPKeyPair signKp, encryptKp;

        PGPPublicKey  signPub = null;
        PGPPrivateKey signPriv = null;
        PGPPublicKey   encPub = null;
        PGPPrivateKey  encPriv = null;

        // public keys
        Iterator<PGPPublicKey> pkeys = pubRing.getPublicKeys();
        while (pkeys.hasNext()) {
            PGPPublicKey key = pkeys.next();
            if (key.isMasterKey()) {
                // master (signing) key
                signPub = key;
            }
            else {
                // sub (encryption) key
                encPub = key;
            }
        }

        // secret keys
        Iterator<PGPSecretKey> skeys = secRing.getSecretKeys();
        while (skeys.hasNext()) {
            PGPSecretKey key = skeys.next();
            if (key.isMasterKey()) {
                // master (signing) key
                signPriv = key.extractPrivateKey(decryptor);
            }
            else {
                // sub (encryption) key
                encPriv = key.extractPrivateKey(decryptor);
            }
        }

        if (encPriv != null && encPub != null && signPriv != null && signPub != null) {
            signKp = new PGPKeyPair(signPub, signPriv);
            encryptKp = new PGPKeyPair(encPub, encPriv);
            return new PersonalKey(signKp, encryptKp, bridgeCert);
        }

        throw new PGPException("invalid key data");
    }

    public static PersonalKey create() throws IOException {
        try {
            PGPDecryptedKeyPairRing kp = PGP.create();
            return new PersonalKey(kp, null);
        }
        catch (Exception e) {
            IOException io = new IOException("unable to generate keypair");
            io.initCause(e);
            throw io;
        }
    }

    /**
     * Searches for the master (signing) key in the given public keyring and
     * signs it with our master key.
     * @return the same public keyring with the signed key. This is suitable to
     * be imported directly into GnuPG.
     * @see #signPublicKey(PGPPublicKey, String)
     */
    @SuppressWarnings("unchecked")
    public PGPPublicKeyRing signPublicKey(byte[] publicKeyring, String id)
            throws PGPException, IOException, SignatureException {

        PGPObjectFactory reader = new PGPObjectFactory(publicKeyring);
        Object o = reader.nextObject();
        while (o != null) {
            if (o instanceof PGPPublicKeyRing) {
                PGPPublicKeyRing pubRing = (PGPPublicKeyRing) o;
                Iterator<PGPPublicKey> iter = pubRing.getPublicKeys();
                while (iter.hasNext()) {
                    PGPPublicKey pk = iter.next();
                    if (pk.isMasterKey()) {
                        PGPPublicKey signed = signPublicKey(pk, id);
                        return PGPPublicKeyRing.insertPublicKey(pubRing, signed);
                    }
                }
            }
            o = reader.nextObject();
        }

        throw new PGPException("invalid keyring data.");
    }

    /**
     * Signs the given public key uid using our master (signing) key.<br>
     * WARNING use this method along with {@link PGPPublicKeyRing#insertPublicKey}
     * to make this effective, otherwise GnuPG will not accept the new signature.
     * @see PGPPublicKeyRing#insertPublicKey(PGPPublicKeyRing, PGPPublicKey)
     * @see #signPublicKey(byte[], String)
     */
    public PGPPublicKey signPublicKey(PGPPublicKey keyToBeSigned, String id)
            throws PGPException, IOException, SignatureException {

        return PGP.signPublicKey(mPair.signKey, keyToBeSigned, id);
    }

    /**
     * Revokes the whole key pair using the master (signing) key.
     * @param store true to store the key in this object
     * @return the revoked master public key
     */
    public PGPPublicKey revoke(boolean store)
            throws PGPException, IOException, SignatureException {

        PGPPublicKey revoked = PGP.revokeKey(mPair.signKey);

        if (store)
            mPair.signKey = new PGPKeyPair(revoked, mPair.signKey.getPrivateKey());

        return revoked;
    }

    /** Stores the public keyring to the system {@link AccountManager}. */
    public void updateAccountManager(Context context)
        throws IOException, InvalidKeyException,
        IllegalStateException, NoSuchAlgorithmException, SignatureException,
        CertificateException, NoSuchProviderException, PGPException, OperatorCreationException {

        AccountManager am = (AccountManager) context.getSystemService(Context.ACCOUNT_SERVICE);
        Account account = Authenticator.getDefaultAccount(am);

        if (account != null) {
            PGPPublicKeyRing pubRing = getPublicKeyRing();

            // regenerate bridge certificate
            byte[] bridgeCertData = X509Bridge.createCertificate(pubRing,
                    mPair.signKey.getPrivateKey(), null).getEncoded();
            byte[] publicKeyData = pubRing.getEncoded();

            am.setUserData(account, Authenticator.DATA_PUBLICKEY,
                Base64.encodeToString(publicKeyData, Base64.NO_WRAP));
            am.setUserData(account, Authenticator.DATA_BRIDGECERT,
                    Base64.encodeToString(bridgeCertData, Base64.NO_WRAP));
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // TODO write byte arrays
        try {
            PGP.toParcel(mPair, dest);
        }
        catch (Exception e) {
            throw new RuntimeException("error writing key to parcel", e);
        }
    }

    public static final Parcelable.Creator<PersonalKey> CREATOR =
            new Parcelable.Creator<PersonalKey>() {
        public PersonalKey createFromParcel(Parcel source) {
            try {
                return new PersonalKey(source);
            }
            catch (Exception e) {
                Log.w(TAG, "error creating from parcel", e);
                return null;
            }
        }

        @Override
        public PersonalKey[] newArray(int size) {
            return new PersonalKey[size];
        };
    };

}
