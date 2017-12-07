/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.yoshio3;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.ListBlobItem;
import com.microsoft.azure.storage.queue.CloudQueue;
import com.microsoft.azure.storage.queue.CloudQueueClient;
import com.microsoft.azure.storage.queue.CloudQueueMessage;
import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.windowsazure.services.media.MediaConfiguration;
import com.microsoft.windowsazure.services.media.MediaContract;
import com.microsoft.windowsazure.services.media.MediaService;
import com.microsoft.windowsazure.services.media.WritableBlobContainerContract;
import com.microsoft.windowsazure.services.media.authentication.*;
import com.microsoft.windowsazure.services.media.models.*;
import com.microsoft.windowsazure.services.queue.QueueConfiguration;
import com.microsoft.windowsazure.services.queue.QueueContract;
import com.microsoft.windowsazure.services.queue.QueueService;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author yoterada
 */
public class RedactFaceService {

    private final static Logger LOGGER = Logger.getLogger(RedactFaceService.class.getName());

    private final static String FILE_PREFIX_NAME = "redacted_face_";
    private final static String FILE_SUFFIX_NAME = "_redacted";
    private final static String TENANT_ID;
    private final static String CLIENT_ID;
    private final static String CLIENT_KEY;
    private final static String REST_API_ENDPOINT;

    private final static String QUEUE_ACCOUNT_KEY;
    private final static String QUEUE_ACCOUNT_NAME;
    private final static String QUEUE_URI;
    private final static String MEDIA_QUEUE_NAME = "media-service-queue";
    
    private final static String DOWNLOAD_DIRECTORY = System.getenv("DOWNLOAD_DIRECTORY");
    

    static {
        TENANT_ID = System.getenv("TENANT_ID");
        CLIENT_ID = System.getenv("CLIENT_ID");
        CLIENT_KEY = System.getenv("CLIENT_KEY");
        REST_API_ENDPOINT = System.getenv("REST_API_ENDPOINT");

        QUEUE_ACCOUNT_KEY = System.getenv("QUEUE_ACCOUNT_KEY");
        QUEUE_ACCOUNT_NAME = System.getenv("QUEUE_ACCOUNT_NAME");
        QUEUE_URI = System.getenv("QUEUE_URI");
    }
    private MediaContract mediaService;
    private ExecutorService executorService;
    private QueueContract queueService;
    private JobNotificationSubscription jobNotificationSubcription;

    public void init() throws MalformedURLException, URISyntaxException, ServiceException {
        executorService = Executors.newFixedThreadPool(1);
        try {
            //アクセス・トークンの取得
            AzureAdTokenCredentials credentials = new AzureAdTokenCredentials(
                    TENANT_ID,
                    new AzureAdClientSymmetricKey(CLIENT_ID, CLIENT_KEY),
                    AzureEnvironments.AZURE_CLOUD_ENVIRONMENT);
            TokenProvider provider = new AzureAdTokenProvider(credentials, executorService);
            Configuration configuration = MediaConfiguration.configureWithAzureAdTokenProvider(
                    new URI(REST_API_ENDPOINT),
                    provider);

            //メディア・サービス　インスタンス生成 
            mediaService = MediaService.create(configuration);

            Configuration queueConfig = Configuration.getInstance();
            queueConfig.setProperty(QueueConfiguration.ACCOUNT_KEY, QUEUE_ACCOUNT_KEY);
            queueConfig.setProperty(QueueConfiguration.ACCOUNT_NAME, QUEUE_ACCOUNT_NAME);
            queueConfig.setProperty(QueueConfiguration.URI, QUEUE_URI);
            //Queue サービス
            queueService = QueueService.create(queueConfig);

            queueService.createQueue(MEDIA_QUEUE_NAME);

            String notificationEndPointName = UUID.randomUUID().toString();
            mediaService.create(NotificationEndPoint.create(notificationEndPointName, EndPointType.AzureQueue, MEDIA_QUEUE_NAME));

            ListResult<NotificationEndPointInfo> listNotificationEndPointInfos = mediaService.list(NotificationEndPoint.list());
            String notificationEndPointId = null;

            for (NotificationEndPointInfo notificationEndPointInfo : listNotificationEndPointInfos) {
                if (notificationEndPointInfo.getName().equals(notificationEndPointName)) {
                    notificationEndPointId = notificationEndPointInfo.getId();
                }
            }
            jobNotificationSubcription = getJobNotificationSubscription(notificationEndPointId, TargetJobState.All);
            LOGGER.log(Level.INFO, "Media Service Initialize finished.");
        } catch (MalformedURLException | URISyntaxException ex) {
            throw ex;
        }
    }

    public void desroy() {
        if (executorService != null) {
            executorService.shutdown();
        }
        LOGGER.log(Level.INFO, "Shutdown Completed.");
    }

    public ListResult<AssetInfo> listAssetID() throws ServiceException {
        // アセットの一覧取得
        ListResult<AssetInfo> assets = mediaService.list(Asset.list());
        assets.forEach(asset -> LOGGER.log(Level.INFO, "{0} : {1}", new String[]{asset.getName(), asset.getId()}));
        return assets;
    }

    public void deleteAll() throws ServiceException {
        // アセットの一覧取得
        ListResult<AssetInfo> assets = mediaService.list(Asset.list());
        assets.forEach((AssetInfo assetInfo) -> {
            try{
                LOGGER.log(Level.SEVERE, null, assetInfo.getName());
                mediaService.delete(Asset.delete(assetInfo.getId()));
            } catch (ServiceException ex) {
                LOGGER.log(Level.SEVERE, null, ex);
            }
        });
    }

    public JobInfo redactFaceFromVideo(AssetInfo assetInfo) throws ServiceException, Exception {
        String assetId = assetInfo.getId();
        String assetName = assetInfo.getName();

        JobInfo job;
        //MediaProcessor 情報の取得
        ListResult<MediaProcessorInfo> mediaProcessors = mediaService.list(MediaProcessor.list());
        for (MediaProcessorInfo mpi : mediaProcessors) {
            if (mpi.getName().equals("Azure Media Redactor")) {
                String id = mpi.getId();
                String outputFileName = FILE_PREFIX_NAME + assetName;

                String taskBody = "<?xml version=\"1.0\" encoding=\"utf-8\"?><taskBody><inputAsset>JobInputAsset(0)</inputAsset><outputAsset assetCreationOptions=\"0\" assetName=\"" + outputFileName + "\">JobOutputAsset(0)</outputAsset></taskBody>";

                Task.CreateBatchOperation task = Task.create(id, taskBody);
                task.setName("Azure Media Redactor for " + outputFileName)
                        .setConfiguration("{'version':'1.0', 'options': {'Mode': 'Combined', 'BlurType': 'High'}}");

                Job.Creator jobCreator = Job.create()
                        .setName("Indexing Job for " + outputFileName)
                        .addInputMediaAsset(assetId)
                        .setPriority(0)
                        .addJobNotificationSubscription(jobNotificationSubcription)
                        .addTaskCreator(task);

                job = mediaService.create(jobCreator);
                String jobId = job.getId();
                LOGGER.log(Level.INFO, "Created Job with Id: {0}", jobId);

                return job;
            }
        }
        return null;
    }

    public void publishContents(JobInfo job) throws ServiceException, IOException {
        ListResult<AssetInfo> outputAssets = mediaService.list(Asset.list(job.getOutputAssetsLink()));
        AssetInfo assetInfo = outputAssets.get(0);

        // Create an access policy that provides Read access for 15 minutes.
        AccessPolicyInfo downloadAccessPolicy = mediaService.create(AccessPolicy.create("Download", 15.0, EnumSet.of(AccessPolicyPermission.READ)));

        // Create a locator using the access policy and asset.
        // This will provide the location information needed to access the asset.
        LocatorInfo locatorInfo = mediaService.create(Locator.create(downloadAccessPolicy.getId(), assetInfo.getId(), LocatorType.SAS));

        // Iterate through the files associated with the asset.
        for (AssetFileInfo assetFile : mediaService.list(AssetFile.list(assetInfo.getAssetFilesLink()))) {
            String file = assetFile.getName();
            String locatorPath = locatorInfo.getPath();
            int startOfSas = locatorPath.indexOf("?");
            String blobPath = locatorPath + file;
            if (startOfSas >= 0) {
                blobPath = locatorPath.substring(0, startOfSas) + "/" + file + locatorPath.substring(startOfSas);
            }
            LOGGER.log(Level.INFO, "Path to asset file: " + blobPath);
        }
    }

    private String getStorageConnectionString() {
        StringBuilder builder = new StringBuilder();
        builder.append("DefaultEndpointsProtocol=https;")
                .append("AccountName=")
                .append(QUEUE_ACCOUNT_NAME)
                .append(";AccountKey=")
                .append(QUEUE_ACCOUNT_KEY)
                .append(";EndpointSuffix=core.windows.net");
        return builder.toString();
    }

    public boolean checkFinish(JobInfo job) throws ServiceException, URISyntaxException, StorageException, InvalidKeyException {
        boolean finished;

        String storageConnectionString = getStorageConnectionString();

        CloudStorageAccount storageAccount = CloudStorageAccount.parse(storageConnectionString);
        CloudQueueClient queueClient = storageAccount.createCloudQueueClient();
        CloudQueue queue = queueClient.getQueueReference(MEDIA_QUEUE_NAME);
        CloudQueueMessage retrieveMessage = queue.retrieveMessage();
        if (retrieveMessage == null) {
            return false;
        }
        String message = retrieveMessage.getMessageContentAsString();
        LOGGER.log(Level.INFO, message);
        if (message.contains("\"NewState\":\"Finished\"")) {
            LOGGER.log(Level.INFO, "FINISHED");
            finished = true;
        } else {
            finished = false;
        }
        queue.deleteMessage(retrieveMessage);
        return finished;
    }


    private JobNotificationSubscription getJobNotificationSubscription(String jobNotificationSubscriptionId,
            TargetJobState targetJobState) {
        return new JobNotificationSubscription(jobNotificationSubscriptionId, targetJobState);
    }

    public AssetInfo uploadFileAndCreateAsset(String assetName, String fileName)
            throws ServiceException, FileNotFoundException, NoSuchAlgorithmException {

        WritableBlobContainerContract uploader;
        AssetInfo resultAsset;
        AccessPolicyInfo uploadAccessPolicy;
        LocatorInfo uploadLocator = null;

        // Create an Asset
        resultAsset = mediaService.create(Asset.create().setName(assetName).setAlternateId("altId"));
        LOGGER.log(Level.INFO, "Created Asset " + fileName);

        // Create an AccessPolicy that provides Write access for 15 minutes
        uploadAccessPolicy = mediaService
                .create(AccessPolicy.create("uploadAccessPolicy", 15.0, EnumSet.of(AccessPolicyPermission.WRITE)));

        // Create a Locator using the AccessPolicy and Asset
        uploadLocator = mediaService
                .create(Locator.create(uploadAccessPolicy.getId(), resultAsset.getId(), LocatorType.SAS));

        // Create the Blob Writer using the Locator
        uploader = mediaService.createBlobWriter(uploadLocator);

        File file = new File(fileName);

        // The local file that will be uploaded to your Media Services account
        InputStream input = new FileInputStream(file);

        LOGGER.log(Level.INFO, "Uploading {0}", fileName);

        // Upload the local file to the media asset
        uploader.createBlockBlob(file.getName(), input);

        // Inform Media Services about the uploaded files
        mediaService.action(AssetFile.createFileInfos(resultAsset.getId()));
        LOGGER.log(Level.INFO, "Uploaded Asset File {0}", fileName);

        mediaService.delete(Locator.delete(uploadLocator.getId()));
        mediaService.delete(AccessPolicy.delete(uploadAccessPolicy.getId()));

        return resultAsset;
    }
    
    
    public void downLoadRedactedImageFile(ListResult<AssetInfo> assets) throws URISyntaxException, InvalidKeyException {
        String storageConnectionString = getStorageConnectionString();
        // Retrieve storage account from connection-string.
        CloudStorageAccount storageAccount = CloudStorageAccount.parse(storageConnectionString);

        // Create the blob client.
        CloudBlobClient blobClient = storageAccount.createCloudBlobClient();

        assets.listIterator().forEachRemaining((AssetInfo assetInfo) -> {
            try {
                String getUri = assetInfo.getUri();
                URI uri = new URI(getUri);
                String path = uri.getPath();
                String container = path.replaceFirst("/", "");

                CloudBlobContainer blobContainer = blobClient.getContainerReference(container);

                blobContainer.listBlobs().forEach((ListBlobItem blobItem) -> {
                    if (blobItem instanceof CloudBlob) {
                        // Download the item and save it to a file with the same name.
                        CloudBlob blob = (CloudBlob) blobItem;
                        String fileName = blob.getName();
                        LOGGER.log(Level.INFO, "DOWNLOAD File name : {0}", fileName);
                        if (fileName.contains(FILE_SUFFIX_NAME)) {
                            try {
                                blob.download(new FileOutputStream(DOWNLOAD_DIRECTORY + fileName));
                            } catch (FileNotFoundException | StorageException ex) {
                                LOGGER.log(Level.SEVERE, null, ex);
                            }
                        }
                    }
                });
                LOGGER.log(Level.INFO, "Delete File name : {0}", assetInfo.getUri());
                mediaService.delete(Asset.delete(assetInfo.getId()));

            } catch (URISyntaxException | StorageException | ServiceException ex) {
                LOGGER.log(Level.SEVERE, null, ex);
            }
        });
    }

}
