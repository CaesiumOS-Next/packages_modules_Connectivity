/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.net.ct;

import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.VisibleForTesting;

import com.android.connectivity.resources.R;
import com.android.server.connectivity.ConnectivityResources;

import org.bouncycastle.util.io.pem.PemReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/** Verifier of the log list signature. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
public class SignatureVerifier {

    private final Context mContext;
    private final ConnectivityResources mConnectivityResources;

    @NonNull private Optional<PublicKey> mPublicKey = Optional.empty();

    public SignatureVerifier(Context context) {
        mContext = context;
        mConnectivityResources = new ConnectivityResources(context);
    }

    @VisibleForTesting
    Optional<PublicKey> getPublicKey() {
        return mPublicKey;
    }

    void resetPublicKey() {
        mPublicKey = Optional.empty();
    }

    void setPublicKeyFrom(Uri file) throws GeneralSecurityException, IOException {
        try (InputStream fileStream = mContext.getContentResolver().openInputStream(file)) {
            setPublicKey(new String(fileStream.readAllBytes()));
        }
    }

    void setPublicKeyFromResources() throws GeneralSecurityException, IOException {
        try (PemReader reader =
                new PemReader(
                        new InputStreamReader(
                                mConnectivityResources
                                        .get()
                                        .openRawResource(R.raw.public_key))); ) {
            setPublicKey(
                    KeyFactory.getInstance("RSA")
                            .generatePublic(
                                    new X509EncodedKeySpec(reader.readPemObject().getContent())));
        }
    }

    void setPublicKey(String publicKey) throws GeneralSecurityException {
        try {
            setPublicKey(
                    KeyFactory.getInstance("RSA")
                            .generatePublic(
                                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Invalid public key", e);
        }
    }

    @VisibleForTesting
    void setPublicKey(PublicKey publicKey) {
        mPublicKey = Optional.of(publicKey);
    }

    boolean verify(Uri file, Uri signature)
            throws GeneralSecurityException, IOException, MissingPublicKeyException {
        if (!mPublicKey.isPresent()) {
            throw new MissingPublicKeyException("Missing public key for signature verification");
        }
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(mPublicKey.get());
        ContentResolver contentResolver = mContext.getContentResolver();

        try (InputStream fileStream = contentResolver.openInputStream(file);
                InputStream signatureStream = contentResolver.openInputStream(signature)) {
            verifier.update(fileStream.readAllBytes());
            return verifier.verify(signatureStream.readAllBytes());
        }
    }
}
