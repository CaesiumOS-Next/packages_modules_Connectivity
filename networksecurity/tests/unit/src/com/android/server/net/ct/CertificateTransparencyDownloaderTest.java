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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.net.ct.DownloadHelper.DownloadStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;

/** Tests for the {@link CertificateTransparencyDownloader}. */
@RunWith(JUnit4.class)
public class CertificateTransparencyDownloaderTest {

    @Mock private DownloadHelper mDownloadHelper;
    @Mock private CertificateTransparencyInstaller mCertificateTransparencyInstaller;

    private PrivateKey mPrivateKey;
    private PublicKey mPublicKey;
    private Context mContext;
    private File mTempFile;
    private DataStore mDataStore;
    private SignatureVerifier mSignatureVerifier;
    private CertificateTransparencyDownloader mCertificateTransparencyDownloader;

    private long mNextDownloadId = 666;

    @Before
    public void setUp() throws IOException, GeneralSecurityException {
        MockitoAnnotations.initMocks(this);

        KeyPairGenerator instance = KeyPairGenerator.getInstance("RSA");
        KeyPair keyPair = instance.generateKeyPair();
        mPrivateKey = keyPair.getPrivate();
        mPublicKey = keyPair.getPublic();

        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mTempFile = File.createTempFile("datastore-test", ".properties");
        mDataStore = new DataStore(mTempFile);
        mDataStore.load();
        mSignatureVerifier = new SignatureVerifier(mContext);

        mCertificateTransparencyDownloader =
                new CertificateTransparencyDownloader(
                        mContext,
                        mDataStore,
                        mDownloadHelper,
                        mSignatureVerifier,
                        mCertificateTransparencyInstaller);
    }

    @After
    public void tearDown() {
        mTempFile.delete();
        mSignatureVerifier.resetPublicKey();
    }

    @Test
    public void testDownloader_startPublicKeyDownload() {
        String publicKeyUrl = "http://test-public-key.org";
        long downloadId = prepareDownloadId(publicKeyUrl);

        assertThat(mCertificateTransparencyDownloader.isPublicKeyDownloadId(downloadId)).isFalse();
        mCertificateTransparencyDownloader.startPublicKeyDownload(publicKeyUrl);
        assertThat(mCertificateTransparencyDownloader.isPublicKeyDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_startMetadataDownload() {
        String metadataUrl = "http://test-metadata.org";
        long downloadId = prepareDownloadId(metadataUrl);

        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(downloadId)).isFalse();
        mCertificateTransparencyDownloader.startMetadataDownload(metadataUrl);
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_startContentDownload() {
        String contentUrl = "http://test-content.org";
        long downloadId = prepareDownloadId(contentUrl);

        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(downloadId)).isFalse();
        mCertificateTransparencyDownloader.startContentDownload(contentUrl);
        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(downloadId)).isTrue();
    }

    @Test
    public void testDownloader_publicKeyDownloadSuccess_updatePublicKey_startMetadataDownload()
            throws Exception {
        long publicKeyId = prepareSuccessfulDownload(Config.PUBLIC_KEY_URL_KEY);
        when(mDownloadHelper.getUri(publicKeyId)).thenReturn(writePublicKeyToUri(mPublicKey));
        long metadataId =
                preparePendingDownload(Config.METADATA_URL_PENDING, "http://test-metadata.org");

        assertThat(mSignatureVerifier.getPublicKey().isPresent()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(publicKeyId));

        Optional<PublicKey> publicKey = mSignatureVerifier.getPublicKey();
        assertThat(publicKey.isPresent()).isTrue();
        assertThat(publicKey.get()).isEqualTo(mPublicKey);
        assertThat(mCertificateTransparencyDownloader.isMetadataDownloadId(metadataId)).isTrue();
    }

    @Test
    public void
            testDownloader_publicKeyDownloadSuccess_updatePublicKeyFail_doNotStartMetadataDownload()
                    throws Exception {
        long publicKeyId = prepareSuccessfulDownload(Config.PUBLIC_KEY_URL_KEY);
        when(mDownloadHelper.getUri(publicKeyId))
                .thenReturn(writeToUri("i_am_not_a_base64_encoded_public_key".getBytes()));
        preparePendingDownload(Config.METADATA_URL_PENDING, "http://test-metadata.org");

        assertThat(mSignatureVerifier.getPublicKey().isPresent()).isFalse();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(publicKeyId));

        assertThat(mSignatureVerifier.getPublicKey().isPresent()).isFalse();
        verify(mDownloadHelper, never()).startDownload(anyString());
    }

    @Test
    public void testDownloader_publicKeyDownloadFail_doNotUpdatePublicKey() throws Exception {
        long publicKeyId =
                prepareFailedDownload(
                        Config.PUBLIC_KEY_URL_KEY,
                        DownloadManager.ERROR_INSUFFICIENT_SPACE,
                        DownloadManager.ERROR_HTTP_DATA_ERROR);
        Intent downloadCompleteIntent = makeDownloadCompleteIntent(publicKeyId);
        preparePendingDownload(Config.METADATA_URL_PENDING, "http://test-metadata.org");

        assertThat(mSignatureVerifier.getPublicKey().isPresent()).isFalse();
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);

        assertThat(mSignatureVerifier.getPublicKey().isPresent()).isFalse();
        verify(mDownloadHelper, never()).startDownload(anyString());
    }

    @Test
    public void testDownloader_metadataDownloadSuccess_startContentDownload() {
        long metadataId = prepareSuccessfulDownload(Config.METADATA_URL_KEY);
        long contentId =
                preparePendingDownload(Config.CONTENT_URL_PENDING, "http://test-content.org");

        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(metadataId));

        assertThat(mCertificateTransparencyDownloader.isContentDownloadId(contentId)).isTrue();
    }

    @Test
    public void testDownloader_metadataDownloadFail_doNotStartContentDownload() {
        long metadataId =
                prepareFailedDownload(
                        Config.METADATA_URL_KEY,
                        DownloadManager.ERROR_INSUFFICIENT_SPACE,
                        DownloadManager.ERROR_HTTP_DATA_ERROR);
        Intent downloadCompleteIntent = makeDownloadCompleteIntent(metadataId);
        preparePendingDownload(Config.CONTENT_URL_PENDING, "http://test-content.org");

        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);

        verify(mDownloadHelper, never()).startDownload(anyString());
    }

    @Test
    public void testDownloader_contentDownloadSuccess_installSuccess_updateDataStore()
            throws Exception {
        File logListFile = File.createTempFile("log_list", "json");
        File metadataFile = sign(logListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);
        mDataStore.setProperty(Config.VERSION_PENDING, "456");
        prepareSuccessfulMetadataWithFile(metadataFile);
        long contentId = prepareSuccessfulContentWithFile(logListFile);
        when(mCertificateTransparencyInstaller.install(
                        eq(Config.COMPATIBILITY_VERSION), any(), anyString()))
                .thenReturn(true);

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        assertInstallSuccessful();
    }

    @Test
    public void testDownloader_contentDownloadFail_doNotInstall() throws Exception {
        long contentId =
                prepareFailedDownload(
                        Config.CONTENT_URL_KEY,
                        DownloadManager.ERROR_INSUFFICIENT_SPACE,
                        DownloadManager.ERROR_HTTP_DATA_ERROR);
        Intent downloadCompleteIntent = makeDownloadCompleteIntent(contentId);

        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);
        mCertificateTransparencyDownloader.onReceive(mContext, downloadCompleteIntent);

        verify(mCertificateTransparencyInstaller, never()).install(any(), any(), any());
        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_contentDownloadSuccess_installFail_doNotUpdateDataStore()
            throws Exception {
        File logListFile = File.createTempFile("log_list", "json");
        File metadataFile = sign(logListFile);
        mSignatureVerifier.setPublicKey(mPublicKey);
        mDataStore.setProperty(Config.VERSION_PENDING, "456");
        prepareSuccessfulMetadataWithFile(metadataFile);
        long contentId = prepareSuccessfulContentWithFile(logListFile);
        when(mCertificateTransparencyInstaller.install(
                        eq(Config.COMPATIBILITY_VERSION), any(), anyString()))
                .thenReturn(false);

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_contentDownloadSuccess_verificationFail_doNotInstall()
            throws IOException {
        File logListFile = File.createTempFile("log_list", "json");
        File metadataFile = File.createTempFile("log_list-wrong_metadata", "sig");
        mSignatureVerifier.setPublicKey(mPublicKey);
        mDataStore.setProperty(Config.VERSION_PENDING, "456");
        prepareSuccessfulMetadataWithFile(metadataFile);
        long contentId = prepareSuccessfulContentWithFile(logListFile);

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        verify(mCertificateTransparencyInstaller, never())
                .install(eq(Config.COMPATIBILITY_VERSION), any(), anyString());
        assertNoVersionIsInstalled();
    }

    @Test
    public void testDownloader_contentDownloadSuccess_missingVerificationPublicKey_doNotInstall()
            throws Exception {
        File logListFile = File.createTempFile("log_list", "json");
        File metadataFile = sign(logListFile);
        mSignatureVerifier.resetPublicKey();
        mDataStore.setProperty(Config.VERSION_PENDING, "456");
        prepareSuccessfulMetadataWithFile(metadataFile);
        long contentId = prepareSuccessfulContentWithFile(logListFile);

        assertNoVersionIsInstalled();
        mCertificateTransparencyDownloader.onReceive(
                mContext, makeDownloadCompleteIntent(contentId));

        verify(mCertificateTransparencyInstaller, never())
                .install(eq(Config.COMPATIBILITY_VERSION), any(), anyString());
        assertNoVersionIsInstalled();
    }

    private void assertNoVersionIsInstalled() {
        assertThat(mDataStore.getProperty(Config.VERSION)).isNull();
        assertThat(mDataStore.getProperty(Config.CONTENT_URL)).isNull();
        assertThat(mDataStore.getProperty(Config.METADATA_URL)).isNull();
    }

    private void assertInstallSuccessful() {
        assertThat(mDataStore.getProperty(Config.VERSION))
                .isEqualTo(mDataStore.getProperty(Config.VERSION_PENDING));
        assertThat(mDataStore.getProperty(Config.CONTENT_URL))
                .isEqualTo(mDataStore.getProperty(Config.CONTENT_URL_PENDING));
        assertThat(mDataStore.getProperty(Config.METADATA_URL))
                .isEqualTo(mDataStore.getProperty(Config.METADATA_URL_PENDING));
    }

    private Intent makeDownloadCompleteIntent(long downloadId) {
        return new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
    }

    private long prepareDownloadId(String url) {
        long downloadId = mNextDownloadId++;
        when(mDownloadHelper.startDownload(url)).thenReturn(downloadId);
        return downloadId;
    }

    private long preparePendingDownload(String propertyKey, String url) {
        long downloadId = mNextDownloadId++;
        when(mDownloadHelper.startDownload(url)).thenReturn(downloadId);
        mDataStore.setProperty(propertyKey, url);
        return downloadId;
    }

    private long prepareSuccessfulDownload(String propertyKey) {
        long downloadId = mNextDownloadId++;
        mDataStore.setPropertyLong(propertyKey, downloadId);
        when(mDownloadHelper.getDownloadStatus(downloadId))
                .thenReturn(makeSuccessfulDownloadStatus(downloadId));
        return downloadId;
    }

    private long prepareSuccessfulMetadataWithFile(File file) {
        long downloadId = prepareSuccessfulDownloadWithFile(Config.METADATA_URL_KEY, file);
        mDataStore.setProperty(
                Config.METADATA_URL_PENDING, "http://metadata-was-downloaded-here.org");
        return downloadId;
    }

    private long prepareSuccessfulContentWithFile(File file) {
        long downloadId = prepareSuccessfulDownloadWithFile(Config.CONTENT_URL_KEY, file);
        mDataStore.setProperty(
                Config.CONTENT_URL_PENDING, "http://content-was-downloaded-here.org");
        return downloadId;
    }

    private long prepareSuccessfulDownloadWithFile(String propertyKey, File file) {
        long downloadId = prepareSuccessfulDownload(propertyKey);
        when(mDownloadHelper.getUri(downloadId)).thenReturn(Uri.fromFile(file));
        return downloadId;
    }

    private long prepareFailedDownload(String propertyKey, int... downloadManagerErrors) {
        long downloadId = mNextDownloadId++;
        mDataStore.setPropertyLong(propertyKey, downloadId);
        DownloadStatus firstError =
                DownloadStatus.builder()
                        .setDownloadId(downloadId)
                        .setStatus(DownloadManager.STATUS_FAILED)
                        .setReason(downloadManagerErrors[0])
                        .build();
        DownloadStatus[] otherErrors = new DownloadStatus[downloadManagerErrors.length - 1];
        for (int i = 1; i < downloadManagerErrors.length; i++) {
            otherErrors[i - 1] =
                    DownloadStatus.builder()
                            .setDownloadId(downloadId)
                            .setStatus(DownloadManager.STATUS_FAILED)
                            .setReason(downloadManagerErrors[i])
                            .build();
        }
        when(mDownloadHelper.getDownloadStatus(downloadId)).thenReturn(firstError, otherErrors);
        return downloadId;
    }

    private void setUpContentDownloadCompleteSuccessful(
            String version, long metadataId, Uri metadataUri, long contentId, Uri contentUri)
            throws IOException {
        mDataStore.setProperty(Config.VERSION_PENDING, version);

        mDataStore.setPropertyLong(Config.METADATA_URL_KEY, metadataId);
        mDataStore.setProperty(Config.METADATA_URL_PENDING, metadataUri.toString());
        when(mDownloadHelper.getUri(metadataId)).thenReturn(metadataUri);

        mDataStore.setPropertyLong(Config.CONTENT_URL_KEY, contentId);
        mDataStore.setProperty(Config.CONTENT_URL_PENDING, contentUri.toString());
        when(mDownloadHelper.getDownloadStatus(contentId))
                .thenReturn(makeSuccessfulDownloadStatus(contentId));
        when(mDownloadHelper.getUri(contentId)).thenReturn(contentUri);
    }

    private DownloadStatus makeSuccessfulDownloadStatus(long downloadId) {
        return DownloadStatus.builder()
                .setDownloadId(downloadId)
                .setStatus(DownloadManager.STATUS_SUCCESSFUL)
                .build();
    }

    private Uri writePublicKeyToUri(PublicKey publicKey)
            throws IOException, GeneralSecurityException {
        return writeToUri(Base64.getEncoder().encode(publicKey.getEncoded()));
    }

    private Uri writeToUri(byte[] bytes) throws IOException, GeneralSecurityException {
        File file = File.createTempFile("temp_file", "tmp");

        try (OutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(bytes);
        }

        return Uri.fromFile(file);
    }

    private File sign(File file) throws IOException, GeneralSecurityException {
        File signatureFile = File.createTempFile("log_list-metadata", "sig");
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(mPrivateKey);

        try (InputStream fileStream = new FileInputStream(file);
                OutputStream outputStream = new FileOutputStream(signatureFile)) {
            signer.update(fileStream.readAllBytes());
            outputStream.write(signer.sign());
        }

        return signatureFile;
    }
}
