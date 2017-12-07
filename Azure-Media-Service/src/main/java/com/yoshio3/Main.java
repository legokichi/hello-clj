package com.yoshio3;

import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.windowsazure.services.media.models.*;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class Main {

    private final static Logger LOGGER = Logger.getLogger(Main.class.getName());
    private final static String UPLOAD_DIRECTORY = System.getenv("UPLOAD_DIRECTORY");

    public static void main(String[] args) {
        try {
            Main main = new Main();
            RedactFaceService redactService = new RedactFaceService();
            redactService.init();
            redactService.listAssetID();
            redactService.deleteAll();
            main.executeMainService();
        } catch (InvalidKeyException | URISyntaxException | ServiceException | NoSuchAlgorithmException | IOException ex) {
            LOGGER.log(Level.SEVERE, null, ex);
        }
    }

    private void executeMainService() throws InvalidKeyException, MalformedURLException, URISyntaxException, ServiceException, FileNotFoundException, NoSuchAlgorithmException, IOException {
        RedactFaceService redactService = new RedactFaceService();
        //初期化
        redactService.init();

        // ファイルのアップロード
        executeFileUpload(redactService);

        //現在アップロードされているアセット一覧の取得
        ListResult<AssetInfo> beforeListAssets = redactService.listAssetID();
        //マスク処理
        executeRedatOperation(redactService, beforeListAssets);
        //ファイルのダウンロード
        ListResult<AssetInfo> afterListAssets = redactService.listAssetID();
        redactService.downLoadRedactedImageFile(afterListAssets);

        //TODO アセットの削除
        //終了処理
        redactService.desroy();
    }

    private void executeFileUpload(RedactFaceService redactService) throws ServiceException, FileNotFoundException, NoSuchAlgorithmException, IOException {
        //ディレクトリ配下のファイル一覧を取得
        Stream<Path> files = Files.list(Paths.get(UPLOAD_DIRECTORY));
        files.forEach((Path path) -> {
            try {
                System.out.println("file name" + path.getFileName().toString());
                System.out.println("Abusolute Path name" + path.toAbsolutePath().toString());                
                redactService.uploadFileAndCreateAsset(path.getFileName().toString(), path.toAbsolutePath().toString());
            } catch (ServiceException | FileNotFoundException | NoSuchAlgorithmException ex) {
                LOGGER.log(Level.SEVERE, null, ex);
            }
        });
    }

    private void executeRedatOperation(RedactFaceService redactService, ListResult<AssetInfo> listAssetID) {
        // アップロードされているファイルを対象にマスク処理
        listAssetID.stream().forEach((AssetInfo asset) -> {
            try {
                //画像へのマスク処理を開始
                JobInfo job = redactService.redactFaceFromVideo(asset);
                if (job != null) {
                    //マスク処理が完了まで待機
                    while (!redactService.checkFinish(job)) {
                        Thread.sleep(1000);
                    }
                    //編集後の動画を外部へ公開したい場合
                    //redactService.publishContents(job);
                }
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, null, ex);
            }
        });
    }
}
