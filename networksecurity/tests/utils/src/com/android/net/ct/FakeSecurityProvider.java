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
 */ package com.android.net.ct;

import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;

/** Who needs real security? */
public class FakeSecurityProvider extends Provider {

    private static final String ACCEPT_ALL_SIGNATURES = "accept_all";

    public FakeSecurityProvider() {
        super("FakeSecurityProvider", 6.66, "Fake provider for tests");
        put("Signature.SHA256withRSA", FakeSecurityProvider.FakeSignature.class.getName());
    }

    /** Sets the provider to accept all signatures */
    public void acceptAllSignatures() {
        Security.setProperty(ACCEPT_ALL_SIGNATURES, "1");
    }

    /** Sets the provider to reject all signatures */
    public void failAllSignatures() {
        Security.setProperty(ACCEPT_ALL_SIGNATURES, "0");
    }

    public static final class FakeSignature extends Signature {

        public FakeSignature() {
            this("SHA256withRSA");
        }

        protected FakeSignature(String algorithm) {
            super(algorithm);
        }

        @Override
        protected void engineInitVerify(PublicKey publicKey) throws InvalidKeyException {}

        @Override
        protected void engineUpdate(byte[] b, int off, int len) throws SignatureException {}

        @Override
        protected boolean engineVerify(byte[] sigBytes) throws SignatureException {
            return "1".equals(Security.getProperty(ACCEPT_ALL_SIGNATURES));
        }

        @Override
        protected Object engineGetParameter(String param) throws InvalidParameterException {
            throw new UnsupportedOperationException("Unimplemented method 'engineGetParameter'");
        }

        @Override
        protected void engineInitSign(PrivateKey privateKey) throws InvalidKeyException {
            throw new UnsupportedOperationException("Unimplemented method 'engineInitSign'");
        }

        @Override
        protected void engineSetParameter(String param, Object value)
                throws InvalidParameterException {
            throw new UnsupportedOperationException("Unimplemented method 'engineSetParameter'");
        }

        @Override
        protected byte[] engineSign() throws SignatureException {
            throw new UnsupportedOperationException("Unimplemented method 'engineSign'");
        }

        @Override
        protected void engineUpdate(byte b) throws SignatureException {
            throw new UnsupportedOperationException("Unimplemented method 'engineUpdate'");
        }
    }
}
