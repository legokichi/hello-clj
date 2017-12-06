package com.yoshio3;
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
import com.microsoft.windowsazure.services.queue.models.PeekMessagesResult;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.MalformedURLException;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    private final static String FILE_PREFIX_NAME = "redacted_face_";
    private final static String TENANT_ID;
    private final static String CLIENT_ID;
    private final static String CLIENT_KEY;
    private final static String REST_API_ENDPOINT;

    private final static String QUEUE_ACCOUNT_KEY;
    private final static String QUEUE_ACCOUNT_NAME;
    private final static String QUEUE_URI;

    //Queue
    private static final String TEST_QUEUE_PREFIX = "testqueueprefix";

    private final static Logger LOGGER = Logger.getLogger(Main.class.getName());

    static {
        TENANT_ID = PropertyReader.getPropertyValue("TENANT_ID");
        CLIENT_ID = PropertyReader.getPropertyValue("CLIENT_ID");
        CLIENT_KEY = PropertyReader.getPropertyValue("CLIENT_KEY");
        REST_API_ENDPOINT = PropertyReader.getPropertyValue("REST_API_ENDPOINT");

        QUEUE_ACCOUNT_KEY = PropertyReader.getPropertyValue("QUEUE_ACCOUNT_KEY");
        QUEUE_ACCOUNT_NAME = PropertyReader.getPropertyValue("QUEUE_ACCOUNT_NAME");
        QUEUE_URI = PropertyReader.getPropertyValue("QUEUE_URI");
    }

    private MediaContract mediaService;
    private ExecutorService executorService;
    private QueueContract queueService;
    private JobNotificationSubscription jobNotificationSubcription;
    private String queueName;

    public static void main(String[] args) {
        Main main = new Main();
        try {
            main.init();
            ListResult<AssetInfo> listAssetID = main.listAssetID();
            listAssetID.stream().forEach((AssetInfo asset) -> {
                try {
                    //
                    main.redactFaceFromVideo(asset);
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, null, ex);
                }
            });
            main.desroy();
        } catch (ServiceException | MalformedURLException | URISyntaxException e) {

        }
    }

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
            queueName = TEST_QUEUE_PREFIX + "createjobwithnotificationsuccess";
            queueService.createQueue(queueName);

            String notificationEndPointName = UUID.randomUUID().toString();
            mediaService.create(NotificationEndPoint.create(notificationEndPointName, EndPointType.AzureQueue, queueName));

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

    public void redactFaceFromVideo(AssetInfo assetInfo) throws ServiceException, Exception {
        String assetId = assetInfo.getId();
        String assetName = assetInfo.getName();

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

                JobInfo job = mediaService.create(jobCreator);
                String jobId = job.getId();
                LOGGER.log(Level.INFO, "Created Job with Id: {0}", jobId);

                List<PeekMessagesResult.QueueMessage> queueMessages = queueService.peekMessages(queueName).getQueueMessages();
                queueMessages.iterator().forEachRemaining((PeekMessagesResult.QueueMessage queueMessages1) -> {
                    LOGGER.log(Level.INFO, "{0} : {1}", new Object[]{queueMessages1.getMessageId(), queueMessages1.getMessageText()});
                });

                LOGGER.log(Level.INFO, "Job Finished!");
            }
        }

    }

    private JobNotificationSubscription getJobNotificationSubscription(String jobNotificationSubscriptionId,
            TargetJobState targetJobState) {
        return new JobNotificationSubscription(jobNotificationSubscriptionId, targetJobState);
    }

    private AssetInfo uploadFileAndCreateAsset(String assetName, String fileName)
            throws ServiceException, FileNotFoundException, NoSuchAlgorithmException {

        WritableBlobContainerContract uploader;
        AssetInfo resultAsset;
        AccessPolicyInfo uploadAccessPolicy;
        LocatorInfo uploadLocator = null;

        // Create an Asset
        resultAsset = mediaService.create(Asset.create().setName(assetName).setAlternateId("altId"));
        System.out.println("Created Asset " + fileName);

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

        System.out.println("Uploading " + fileName);

        // Upload the local file to the media asset
        uploader.createBlockBlob(file.getName(), input);

        // Inform Media Services about the uploaded files
        mediaService.action(AssetFile.createFileInfos(resultAsset.getId()));
        System.out.println("Uploaded Asset File " + fileName);

        mediaService.delete(Locator.delete(uploadLocator.getId()));
        mediaService.delete(AccessPolicy.delete(uploadAccessPolicy.getId()));

        return resultAsset;
    }
}
