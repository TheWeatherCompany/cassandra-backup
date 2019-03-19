package com.instaclustr.backup.downloader;

import com.ibm.cloud.objectstorage.client.builder.AwsClientBuilder;
import com.ibm.cloud.objectstorage.event.ProgressEvent;
import com.ibm.cloud.objectstorage.event.ProgressEventType;
import com.ibm.cloud.objectstorage.services.s3.AmazonS3;
import com.ibm.cloud.objectstorage.services.s3.AmazonS3ClientBuilder;
import com.ibm.cloud.objectstorage.services.s3.model.GetObjectRequest;
import com.ibm.cloud.objectstorage.services.s3.model.ObjectListing;
import com.ibm.cloud.objectstorage.services.s3.transfer.PersistableTransfer;
import com.ibm.cloud.objectstorage.services.s3.transfer.TransferManager;
import com.ibm.cloud.objectstorage.services.s3.transfer.TransferManagerBuilder;
import com.ibm.cloud.objectstorage.services.s3.transfer.internal.S3ProgressListener;
import com.instaclustr.backup.RestoreArguments;
import com.instaclustr.backup.common.RemoteObjectReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class IBMDownloader extends Downloader {
    private static final Logger logger = LoggerFactory.getLogger(IBMDownloader.class);

    private final AmazonS3 amazonS3;
    private final TransferManager transferManager;

    public IBMDownloader(final RestoreArguments arguments) {
        super(arguments);
        AwsClientBuilder.EndpointConfiguration ec = new AwsClientBuilder.EndpointConfiguration(arguments.endpoint, System.getenv("AWS_REGION"));
        this.amazonS3 = AmazonS3ClientBuilder.standard().withEndpointConfiguration(ec).build();
        this.transferManager = TransferManagerBuilder.standard().withS3Client(this.amazonS3).build();
    }

    static class AWSRemoteObjectReference extends RemoteObjectReference {
        public AWSRemoteObjectReference(Path objectKey, String canonicalPath) {
            super(objectKey, canonicalPath);
        }

        @Override
        public Path getObjectKey() {
            return objectKey;
        }
    }

    @Override
    public RemoteObjectReference objectKeyToRemoteReference(final Path objectKey) {
        return new AWSRemoteObjectReference(objectKey, resolveRemotePath(objectKey));
    }

    @Override
    public void downloadFile(final Path localPath, final RemoteObjectReference object) throws Exception {
        final GetObjectRequest getObjectRequest = new GetObjectRequest(restoreFromBackupBucket, object.canonicalPath);

        S3ProgressListener s3ProgressListener = new S3ProgressListener() {
            @Override
            public void onPersistableTransfer(final PersistableTransfer persistableTransfer) {
                // We don't resume downloads
            }

            @Override
            public void progressChanged(final ProgressEvent progressEvent) {
                if (progressEvent.getEventType() == ProgressEventType.TRANSFER_COMPLETED_EVENT) {
                    logger.debug("Successfully downloaded {}.", object.canonicalPath);
                }
            }
        };

        Files.createDirectories(localPath.getParent());
        transferManager.download(getObjectRequest, localPath.toFile(), s3ProgressListener).waitForCompletion();
    }

    @Override
    public List<RemoteObjectReference> listFiles(final RemoteObjectReference prefix) {
        final AWSRemoteObjectReference awsRemoteObjectReference = (AWSRemoteObjectReference) prefix;
        final Path bucketPath = Paths.get(restoreFromClusterId).resolve(restoreFromNodeId);

        List<RemoteObjectReference> fileList = new ArrayList<>();
        ObjectListing objectListing = amazonS3.listObjects(restoreFromBackupBucket, awsRemoteObjectReference.canonicalPath);

        boolean hasMoreContent = true;

        while (hasMoreContent) {
            objectListing.getObjectSummaries().stream()
                .filter(objectSummary -> !objectSummary.getKey().endsWith("/"))
                .forEach(objectSummary -> fileList.add(objectKeyToRemoteReference(bucketPath.relativize(Paths.get(objectSummary.getKey())))));

            if (objectListing.isTruncated()) {
                objectListing = amazonS3.listNextBatchOfObjects(objectListing);
            } else {
                hasMoreContent = false;
            }
        }

        return fileList;
    }

    @Override
    void cleanup() throws Exception {
        // Nothing to cleanup
        amazonS3.shutdown();
    }
}
