package com.yoshio3;

import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.windowsazure.services.media.MediaConfiguration;
import com.microsoft.windowsazure.services.media.MediaContract;
import com.microsoft.windowsazure.services.media.MediaService;
import com.microsoft.windowsazure.services.media.WritableBlobContainerContract;
import com.microsoft.windowsazure.services.media.authentication.*;
import com.microsoft.windowsazure.services.media.models.*;

import com.microsoft.azure.storage.*;
import com.microsoft.azure.storage.blob.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.MalformedURLException;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.io.IOException;
import java.security.InvalidKeyException;

public class Main {
    private final static String FILE_PREFIX_NAME = "redacted_face_";
    private final static String TENANT_ID;
    private final static String CLIENT_ID;
    private final static String CLIENT_KEY;
    private final static String REST_API_ENDPOINT;

    private final static Logger LOGGER = Logger.getLogger(Main.class.getName());

    static {
        TENANT_ID = PropertyReader.getPropertyValue("TENANT_ID");
        CLIENT_ID = PropertyReader.getPropertyValue("CLIENT_ID");
        CLIENT_KEY = PropertyReader.getPropertyValue("CLIENT_KEY");
        REST_API_ENDPOINT = PropertyReader.getPropertyValue("REST_API_ENDPOINT");
    }

    private MediaContract mediaService;
    private ExecutorService executorService;


    public static void main(String[] args) {
        Main main = new Main();
        try {
            main.init();
            ListResult<AssetInfo> listAssetID = main.listAssetID();
            listAssetID.stream().forEach((AssetInfo asset) -> {
                try {
                    //
                    downloadAsset(asset);
                    //main.redactFaceFromVideo(asset);
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, null, ex);
                }
            });
            main.desroy();
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "UFOOOO." + e.getMessage());
        }
    }

    public void init() throws MalformedURLException, URISyntaxException {
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
            LOGGER.log(Level.INFO, "Media Service Initialize finished.");
        } catch (MalformedURLException | URISyntaxException e) {
            LOGGER.log(Level.INFO, "UFOOOO." + e.getMessage());
            throw e;
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
                        .addInputMediaAsset(assetId).setPriority(0).addTaskCreator(task);

                JobInfo job = mediaService.create(jobCreator);
                String jobId = job.getId();
                LOGGER.log(Level.INFO, "Created Job with Id: {0}", jobId);

                JobState result = checkJobStatus(mediaService, jobId);
                if (result != JobState.Finished) {
                    LOGGER.log(Level.INFO, "The job has finished with a wrong status: {0}", result.toString());
                    throw new RuntimeException();
                }
                // Retrieve the output Asset
                ListResult<AssetInfo> outputAssets = mediaService.list(Asset.list(job.getOutputAssetsLink()));
                for (AssetInfo asset : outputAssets) {
                    System.out.println("outputFileName:" + outputFileName);
                    System.out.println("name:" + asset.getName());
                    System.out.println("uri:" + asset.getUri());
                    System.out.println("storage:" + asset.getStorageAccountName());
                    System.out.println("link:" + asset.getAssetFilesLink());
                    try{
                        downloadAsset(asset);
                    }catch(Exception e){
                        LOGGER.log(Level.INFO, "downloadAsset:" + e.getMessage());
                        throw e;
                    }
                }

                LOGGER.log(Level.INFO, "Job Finished!");
            }
        }

    }

    private static JobState checkJobStatus(MediaContract mediaService, String jobId) throws InterruptedException, ServiceException {
        boolean done = false;
        JobState jobState = null;
        while (!done) {
            // Sleep for 5 seconds
            Thread.sleep(5000);

            // Query the updated Job state
            jobState = mediaService.get(Job.get(jobId)).getState();
            System.out.println("Job state: " + jobState);

            if (jobState == JobState.Finished || jobState == JobState.Canceled || jobState == JobState.Error) {
                System.out.println("Job state: " + jobState.getCode());
                done = true;
            }
        }

        return jobState;
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
    
    private static void downloadAsset(AssetInfo asset) throws URISyntaxException, StorageException, IOException, InvalidKeyException {
        String storageConnectionString = "DefaultEndpointsProtocol=https;AccountName=ino1hackfes1;AccountKey=QaWqvulqF/8m5pFBn8rSabPYj2jCQViIFymEb0O9pHo9lKrFoWJRiwbOykn6sM9KopTCXDl/v6ZhTjgNuIUnJA==;EndpointSuffix=core.windows.net";
        CloudStorageAccount account = CloudStorageAccount.parse(storageConnectionString);
        CloudBlobClient serviceClient = account.createCloudBlobClient();
        // Container name must be lower case.
        CloudBlobContainer container = serviceClient.getContainerReference(asset.getUri().split("blob.core.windows.net")[1]);
        for(ListBlobItem blob : container.listBlobs()){
            System.out.println(blob.getUri());
            String uri = blob.getUri().toString();
            String filenName = uri.split("/")[uri.split("/").length-1];
            System.out.println(filenName);
            // Download the image file.
            CloudBlockBlob _blob = container.getBlockBlobReference(filenName);
            File destinationFile = new File(filenName);
            _blob.downloadToFile(destinationFile.getAbsolutePath());
        }
    }

    private static void download() throws URISyntaxException, StorageException, IOException, InvalidKeyException {
        String storageConnectionString = "DefaultEndpointsProtocol=https;AccountName=ino1hackfes1;AccountKey=QaWqvulqF/8m5pFBn8rSabPYj2jCQViIFymEb0O9pHo9lKrFoWJRiwbOykn6sM9KopTCXDl/v6ZhTjgNuIUnJA==;EndpointSuffix=core.windows.net";
        CloudStorageAccount account = CloudStorageAccount.parse(storageConnectionString);
        CloudBlobClient serviceClient = account.createCloudBlobClient();
        // Container name must be lower case.
        CloudBlobContainer container = serviceClient.getContainerReference("asset-a6dc4934-72b9-4ac2-8968-e229cf9537d6");
        CloudBlockBlob blob = container.getBlockBlobReference("b_redacted_redacted_redacted_redacted_redacted_redacted_redacted.mp4");
        // Download the image file.
        File destinationFile = new File("b_redacted_redacted_redacted_redacted_redacted_redacted_redacted.mp4");
        blob.downloadToFile(destinationFile.getAbsolutePath());
    }
}



