(ns hello-clj.core
  (:import [java.io File FileInputStream FileNotFoundException] )
  (:import [java.net URL URI] )
  (:import [java.util EnumSet] )
  (:import [java.nio.file Files Paths] )
  (:import [java.util.concurrent Executors ExecutorService] )
  (:import [com.microsoft.windowsazure.services.media MediaConfiguration MediaContract MediaService WritableBlobContainerContract] )
  (:import [com.microsoft.windowsazure.services.media.authentication AzureAdClientSymmetricKey AzureAdTokenCredentials AzureAdTokenProvider AzureEnvironments] )
  (:import [com.microsoft.windowsazure.services.media.models
    AccessPolicy AccessPolicyInfo AccessPolicyPermission Asset AssetFile AssetFileInfo AssetInfo Job JobInfo JobState ListResult Locator LocatorInfo LocatorType MediaProcessor MediaProcessorInfo Task] )
  (:use [clojure.string :as string] )
  (:gen-class main true) )

(defn uploadFileAndCreateAsset [mediaService assetName fileName]
  (let [;; Create an Asset
        resultAsset (.create mediaService (.. (Asset/create) (setName assetName) (setAlternateId "altId") ) ) ]
    (println (str "Created Asset " fileName) )
    (let [;; Create an AccessPolicy that provides Write access for 15 minutes
          uploadAccessPolicy (.create mediaService (AccessPolicy/create "uploadAccessPolicy" 15.0 (EnumSet/of AccessPolicyPermission/WRITE) ) )
          ;; Create a Locator using the AccessPolicy and Asset
          uploadLocator (.create mediaService (Locator/create (.getId uploadAccessPolicy) (.getId resultAsset) LocatorType/SAS) )
          ;; Create the Blob Writer using the Locator
          uploader (.createBlobWriter mediaService uploadLocator)
          file (File. fileName)
          ;; The local file that will be uploaded to your Media Services account
          input (FileInputStream. file) ]
      (println (str "Uploading " + fileName))
      ;; Upload the local file to the media asset
      (.createBlockBlob uploader (.getName file) input)
      ;; Inform Media Services about the uploaded files
      (.action mediaService (AssetFile/createFileInfos (.getId resultAsset) ) )
      (println "Uploaded Asset File " + fileName)
      (.delete mediaService (Locator/delete (.getId uploadLocator) ) )
      (.delete mediaService (AccessPolicy/delete (.getId uploadAccessPolicy) ) )
      resultAsset) ) )

(defn checkJobStatus [mediaService jobId]
  (loop [done false
         jobState nil]
    (if (done)
        jobState
        (do
          (Thread/sleep 5000)
          ;; Query the updated Job state
          (let [jobState (.. mediaService (get (Job/get jobId) ) (getState) ) ]
            (println (str "Job state: " jobState) )
            (if (or (== jobState JobState/Finished)
                    (== jobState JobState/Canceled)
                    (== jobState JobState/Error) )
                (recur true jobState)
                (recur false jobState) ) ) ) ) ) )

(defn redactFaceFromVideo [mediaService asset]
;; Create a Job that contains a Task to transform the Asset
  (let [assetId (.getId asset)
        assetName (.getName asset)
        ;; Retrieve the list of Media Processors that match the name
        mediaProcessors (.list mediaService (MediaProcessor/list) )
        ;; Use the latest version of the Media Processor
        redactors (filter (fn [mpi] (.. mpi (getName) (equals "Azure Media Redactor") ) ) mediaProcessors) ]
    (doseq [redactor redactors]
      (println (str "redactor: " (.getName redactor) ": " (.getVersion redactor) ) ) )
    (doseq [redactor (take 1 redactors)]
      (println (str "Using Media Processor: " (.getName redactor) " " (.getVersion redactor) ) )
      (let [id (.getId redactor)
            ;; Create a task with the specified Media Processor
            outputFileName (str "ino_" assetName)
            taskBody (str "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                          "<taskBody><inputAsset>JobInputAsset(0)</inputAsset>"
                          "<outputAsset"
                            "assetCreationOptions=\"0\"" ;; AssetCreationOptions.None
                            "assetName=\"" outputFileName "\">JobOutputAsset(0)</outputAsset>"
                          "</taskBody>")
            task (.. (Task/create id taskBody)
              (setName (str "Azure Media Redactor for " outputFileName) )
              (setConfiguration "{'version':'1.0', 'options': {'Mode': 'Combined', 'BlurType': 'High'}}") )
            jobCreator (.. (Job/create)
              (setName (str "Indexing Job " assetName " to " outputFileName) )
              (addInputMediaAsset assetId)
              (setPriority (int 0) )
              (addTaskCreator task) )
            job (.. mediaService (create jobCreator) )
            jobId (.getId job) ]
        (println (str "Created Job with Id: " jobId) )
        ;; Check to see if the Job has completed
        (let [result (checkJobStatus mediaService jobId)]
          ;; Done with the Job
          (if (not= result JobState/Finished)
            (do (println (str "The job has finished with a wrong status: " (.toString result) ) ) nil)
            (let [;; Retrieve the output Asset
                  outputAssets (.list mediaService (Asset/list (.getOutputAssetsLink job) ) ) ]
              (println "Job Finished!")
              (doseq [asset outputAssets]
                  (println (str "name:" (.getName asset) ) )
                  (println (str "uri:" (.getUri asset) ) )
                  (println (str "storage:" (.getStorageAccountName asset) ) )
                  (println (str "link:" (.getAssetFilesLink asset) ) ) )
              (.get outputAssets 0) ) ) ) ) ) ) )

(defn -main [& args]
  (let [tenant "9bdef4bf-da9d-4e5d-a25a-5935f2dad4d2"
        clientId "ef0a1623-0377-412e-84da-3aec53b0fe75"
        clientKey "a2ojjEQDTkJahM/tICGyAIFF0mP7BhA+6VMxM5m78Jc="
        restApiEndpoint "https://ino1hackfes12017.restv2.japaneast.media.azure.net/api/"
        executorService (Executors/newFixedThreadPool 1)
        ;; Connect to Media Services API with service principal and client symmetric key //アクセス・トークンの取得
        credentials (AzureAdTokenCredentials. tenant (AzureAdClientSymmetricKey. clientId clientKey) AzureEnvironments/AZURE_CLOUD_ENVIRONMENT )
        provider (AzureAdTokenProvider. credentials executorService)
        ;; create a new configuration with the new credentials
        configuration (MediaConfiguration/configureWithAzureAdTokenProvider (URI. restApiEndpoint) provider)
        ;; create the media service provisioned with the new configuration //メディア・サービス　インスタンス生成 
        mediaService (MediaService/create configuration) ]
    (println "Media Service Initialize finished.")
    (println "Azure SDK for Java - Media Analytics Sample (Indexer)")
    (let [uploadAsset (uploadFileAndCreateAsset mediaService "Video Name" "/home/legokichi/b.mp4") ]
      (println (str "Uploaded Asset Id: " (.getId uploadAsset) ) )
      ;;// アセットの一覧取得
      (doseq [asset (.list mediaService (Asset/list))]
        (println (str "uploaded video: " (.getName asset) (.getId asset) ) ) )
      (let [redacted (redactFaceFromVideo mediaService uploadAsset)]
        (println (str "Redacted Asset Id: " (.getId redacted) ) )
        (println "Sample completed!")
        (println "hello clojure") ) ) ) )
