(ns hello-clj.core
  (:import [java.io File FileInputStream] )
  (:import [java.net URL URI] )
  (:import [java.util EnumSet] )
  (:import [java.util.concurrent Executors] )
  (:import [com.microsoft.windowsazure.services.media MediaConfiguration MediaService] )
  (:import [com.microsoft.windowsazure.services.media.authentication AzureAdClientSymmetricKey AzureAdTokenCredentials AzureAdTokenProvider AzureEnvironments] )
  (:import [com.microsoft.windowsazure.services.media.models AccessPolicy AccessPolicyPermission Asset AssetFile Job JobState Locator LocatorType MediaProcessor Task] )
  (:import [com.microsoft.azure.storage CloudStorageAccount] )
  (:gen-class main true) )


(defn uploadFileAndCreateAsset [mediaService assetName fileName]
  (try
    (let [;; Create an Asset
          resultAsset (.. mediaService (create (.. (Asset/create) (setName assetName) (setAlternateId "altId") ) ) )
          ;; Create an AccessPolicy that provides Write access for 15 minutes
          uploadAccessPolicy (.. mediaService (create (AccessPolicy/create "uploadAccessPolicy" 15.0 (EnumSet/of AccessPolicyPermission/WRITE) ) ) )
          ;; Create a Locator using the AccessPolicy and Asset
          uploadLocator (.. mediaService (create (Locator/create (.. uploadAccessPolicy (getId) ) (.. resultAsset (getId) ) LocatorType/SAS) ) )
          ;; Create the Blob Writer using the Locator
          uploader (.. mediaService (createBlobWriter uploadLocator) )
          file (File. fileName)
          ;; The local file that will be uploaded to your Media Services account
          input (FileInputStream. file) ]
      (println "Uploading " fileName)
      ;; Upload the local file to the media asset
      (.. uploader (createBlockBlob (.. file (getName)) input) )
      ;; Inform Media Services about the uploaded files
      (.. mediaService (action (AssetFile/createFileInfos (.. resultAsset (getId) ) ) ) )
      (println "Uploaded Asset File " fileName)
      (.. mediaService (delete (Locator/delete (.. uploadLocator (getId) ) ) ) )
      (.. mediaService (delete (AccessPolicy/delete (.. uploadAccessPolicy (getId) ) ) ) )
      resultAsset)
    (catch Exception e 
      (println "uploadFileAndCreateAsset caught exception: " (.. e (getMessage)) )
      (throw e) ) ) )


(defn downloadAsset [asset]
  (try
    (let [storageConnectionString "DefaultEndpointsProtocol=https;AccountName=ino1hackfes1;x;EndpointSuffix=core.windows.net"
          account (CloudStorageAccount/parse storageConnectionString)
          serviceClient (.. account (createCloudBlobClient) )
          ;; Container name must be lower case.
          container (.. serviceClient (getContainerReference (aget (.. asset (getUri) (split "blob.core.windows.net") ) 1) ) )
          blobItems (.. container listBlobs)]
        (doseq [blobItem blobItems]
          (let [uri (.. blobItem (getUri) (toString) )
                lst (.. uri (split "/") )
                filenName (aget lst (- (alength lst) 1) )
                ;; Download the image file.
                blob (.. container (getBlockBlobReference filenName) )
                destinationFile (File. filenName) ]
            (println "download" filenName)
            (.. blob (downloadToFile (.. destinationFile (getAbsolutePath) ) ) ) ) ) )
    (catch Exception e 
      (println "downloadAsset caught exception: " (.. e (getMessage) ) )
      (throw e) ) ) )


(defn checkJobStatus [mediaService jobId]
  (try
    (loop [doneJobState nil i 0]
      (if (some? doneJobState)
          doneJobState
          (let [;; Query the updated Job state
                jobState (.. mediaService (get (Job/get jobId) ) (getState) )
                ret (if (or (= jobState JobState/Finished)
                            (= jobState JobState/Canceled)
                            (= jobState JobState/Error) )
                        jobState
                        nil) ]
            (println "Job state: " jobState ":" i)
            (Thread/sleep 5000)
            (recur ret (+ i 1) ) ) ) )
    (catch Exception e 
      (println "checkJobStatus caught exception: " (.. e (getMessage) ) )
      (throw e) ) ) )


(defn redactFaceFromVideo [mediaService asset]
  (try
    ;; Create a Job that contains a Task to transform the Asset
    (let [assetId (.. asset (getId) )
          assetName (.. asset (getName) )
          ;; Retrieve the list of Media Processors that match the name
          mediaProcessors (.. mediaService (list  (MediaProcessor/list) ) )
          ;; Use the latest version of the Media Processor
          redactors (filter (fn [proc] (.. proc (getName) (equals "Azure Media Redactor") ) ) mediaProcessors) ]
      (doseq [redactor (take 1 redactors)]
        (println "Using Media Processor: " (.. redactor (getName) ) "@" (.. redactor (getVersion) ) ":" (.. redactor (getId) ) )
        (let [id (.getId redactor)
              ;; Create a task with the specified Media Processor
              outputFileName (str "_" assetName)
              taskBody (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                            "<taskBody>"
                            "<inputAsset>JobInputAsset(0)</inputAsset>"
                            ;; AssetCreationOptions.None
                            "<outputAsset assetCreationOptions=\"0\" assetName=\"" outputFileName "\">JobOutputAsset(0)</outputAsset>"
                            "</taskBody>")
              task (.. (Task/create id taskBody)
                (setName (str "Azure Media Redactor for " outputFileName) )
                (setConfiguration "{'version':'1.0', 'options': {'Mode': 'Combined', 'BlurType': 'High'}}") )
              jobCreator (.. (Job/create)
                (setName (str "Indexing Job for " outputFileName) )
                (addInputMediaAsset assetId)
                (setPriority (int 0) )
                (addTaskCreator task) )
              job (.. mediaService (create jobCreator) )
              jobId (.. job (getId) ) ]
          (println "Created Job with Id: " jobId)
          ;; Check to see if the Job has completed
          (let [result (checkJobStatus mediaService jobId) ]
            ;; Done with the Job
            (if (not= result JobState/Finished)
              (do (println "The job has finished with a wrong status: " (.toString result) )
                  nil)
              (let [;; Retrieve the output Asset
                    outputAssets (.. mediaService (list (Asset/list (.. job (getOutputAssetsLink) ) ) ) ) ]
                (println "Job Finished! :" (count outputAssets) )
                outputAssets) ) ) ) ) )
    (catch Exception e 
      (println "redactFaceFromVideo caught exception: " (.. e (getMessage) ) )
      (throw e) ) ) )

(defn -main [& args]
  (try
    (let [TENANT_ID (or (System/getenv "TENANT_ID") "x")
          CLIENT_ID (or (System/getenv "CLIENT_ID") "x")
          CLIENT_KEY (or (System/getenv "CLIENT_KEY") "x")
          REST_API_ENDPOINT (or (System/getenv "REST_API_ENDPOINT") "https:x")
          INPUT_FILE (or (System/getenv "INPUT_FILE") "/home/legokichi/b.mp4")
          executorService (Executors/newFixedThreadPool 1)
          ;; Connect to Media Services API with service principal and client symmetric key //アクセス・トークンの取得
          credentials (AzureAdTokenCredentials. TENANT_ID (AzureAdClientSymmetricKey. CLIENT_ID CLIENT_KEY) AzureEnvironments/AZURE_CLOUD_ENVIRONMENT )
          provider (AzureAdTokenProvider. credentials executorService)
          ;; create a new configuration with the new credentials
          configuration (MediaConfiguration/configureWithAzureAdTokenProvider (URI. REST_API_ENDPOINT) provider)
          ;; create the media service provisioned with the new configuration //メディア・サービス　インスタンス生成 
          mediaService (MediaService/create configuration) ]
      (println "Media Service Initialize finished.")
      (println "Azure SDK for Java - Media Analytics Sample (Indexer)")
      (let [uploadAsset (uploadFileAndCreateAsset mediaService "TMP.mp4" INPUT_FILE) ]
        (println "Uploaded Asset Id: " (.. uploadAsset (getId) ) )
        (redactFaceFromVideo mediaService uploadAsset)
        (println "redactFaceFromVideo finished.")
        ;;// アセットの一覧取得
        (doseq [asset (.. mediaService (list (Asset/list) ) ) ]
          (println "all uploaded video: " (.getName asset) (.getId asset) )
          (println "name:" (.. asset (getName) ) )
          (println "uri:" (.. asset (getUri) ) )
          (println "storage:" (.. asset (getStorageAccountName) ) )
          (println "link:" (.. asset (getAssetFilesLink)) )
          (downloadAsset asset)
          (println "downloaded") )
        (println "hello clojure") ) )
    (catch Exception e
      (println "main caught exception: " (.. e (getMessage) ) )
      (throw e) ) ) )
