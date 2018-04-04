package net.hakku.visionglass;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.microsoft.projectoxford.vision.VisionServiceClient;
import com.microsoft.projectoxford.vision.VisionServiceRestClient;
import com.microsoft.projectoxford.vision.contract.AnalysisResult;
import com.microsoft.projectoxford.vision.contract.Caption;
import com.microsoft.projectoxford.vision.rest.VisionServiceException;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

public class MainActivity extends AppCompatActivity {
    // 블루투스
    // static final String piBTID = "B8:27:EB:15:58:06"; // rpi3
    // static final String piBTID = "B8:27:EB:5D:D6:10"; // rpi3 with lcd
    static final String piBTID = "B8:27:EB:7F:0D:EE"; // rpi zero
    static final UUID uuid = UUID.fromString("3fef2a50-5c7f-11e7-9598-0800200c9a66");
    static final int SEND_LOG = 0;
    static final int SET_IMG = 1;
    static final int PLEN = 1024;

    BluetoothAdapter mBtAdapter;
    BluetoothDevice mBtDevice;
    BluetoothSocket mBtSocket;
    InputStream mInput;
    OutputStream mOutput;

    // Vision API
    /////////////////////////////////////////////////////////////////////
    private Bitmap mBitmap;
    private VisionServiceClient client;

    /////////////////////////////////////////////////////////////////////

    // TTS
    private TextToSpeech myTTS;

    // Sound
    SoundPool soundPool;
    int soundShutter;
    int soundUi;

    // 기타
    static final int MAX_THINGS_NUM = 3;
    private boolean inProcessing = false;
    private boolean endWalk = false;

    private ImageView ivPreview;
    private TextView tvLog;

    // UI 핸들러
    final Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case SEND_LOG:
                    tvLog.setText((String) msg.obj + "\n" + tvLog.getText());
                    break;
                case SET_IMG:
                    if (msg.obj instanceof Drawable)
                        ivPreview.setImageDrawable((Drawable) msg.obj);
                    else if (msg.obj instanceof Bitmap)
                        ivPreview.setImageBitmap((Bitmap) msg.obj);
                    else
                        log("Image err!");
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 레이아웃 뷰 연결
        ivPreview = (ImageView) findViewById(R.id.ivPreview);
        tvLog = (TextView) findViewById(R.id.tvLog);

        // 블루투스 어뎁터 얻기
        mBtAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBtAdapter == null) {
            Log.d("INFO", "mBtAdapter is null");
        }

        // 장치 얻기
        mBtDevice = mBtAdapter.getRemoteDevice(piBTID);

        // Vision
        if (client==null){
            client = new VisionServiceRestClient("0366570121b6453aac76d44ff2d8e43d", "https://westcentralus.api.cognitive.microsoft.com/vision/v1.0");
        }

        // TTS
        myTTS = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                myTTS.setLanguage(Locale.KOREA);
            }
        });

        // Sound
        soundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 0);
        soundShutter = soundPool.load(this, R.raw.shutter, 1);
        soundUi = soundPool.load(this, R.raw.ui, 1);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch(keyCode)
        {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                Toast.makeText(this, "KeyDown", Toast.LENGTH_SHORT).show();
                if (inProcessing == false) {
                    inProcessing = true;
                    new doGetPictureAndProcess().execute();
                }
                else
                    log("In processing!");
                break;

            case KeyEvent.KEYCODE_VOLUME_UP:
                Toast.makeText(this, "KeyUp", Toast.LENGTH_SHORT).show();
                if (inProcessing == false) {
                    inProcessing = true;
                    new doWalk().execute();
                }
                else
                    endWalk = true;
                break;
            default:
                return super.onKeyDown(keyCode, event);
        }
        return true;
    }

    //////////////////////////////// 사용자 메소드 ////////////////////////////////////////

    private void log(String msg) {
        Log.d("INFO", msg);
        handler.sendMessage(handler.obtainMessage(SEND_LOG, msg));
    }
    private void speakFlush(String str) {
        String utteranceId = this. hashCode() + "";
        myTTS.speak(str, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    private void speakAdd(String str) {
        String utteranceId = this. hashCode() + "";
        myTTS.speak(str, TextToSpeech.QUEUE_ADD, null, utteranceId);
    }

    private void playSound(int soundId) {
        soundPool.play(soundId, 1f, 1f, 0, 0, 1f);
    }

    /////////////////////////////////// Bluetooth /////////////////////////////////////////

    class doWalk extends AsyncTask<String, String, String> {
        public doWalk() {
        }

        @Override
        protected String doInBackground(String... params) {
            try {
                log("");
                log("start");
                playSound(soundUi);
                // 소켓 얻기
                mBtSocket = mBtDevice.createRfcommSocketToServiceRecord(uuid);

                // 데이터 버퍼
                byte[] b = new byte[PLEN];
                int len = 0;

                // 기기에 연결
                mBtSocket.connect();
                log("connected!");
                mInput = mBtSocket.getInputStream();
                mOutput = mBtSocket.getOutputStream();

                // WALK 요청 전송
                mOutput.write("WALK".getBytes());

                while (true) {
                    // 종료 명령 확인
                    if (endWalk) {
                        mOutput.write("END".getBytes());
                        mBtSocket.close();
                        endWalk = false;
                        inProcessing = false;
                        log("disconnected");
                        playSound(soundUi);
                        break;
                    }
                    if (mInput.available() != 0) {
                        len = mInput.read(b, 0, PLEN);
                        String guide = new String(b, 0, len);
                        log(guide);
                        if (guide.equals("RIGHT")) {
                            speakFlush("오른쪽으로 가세요.");
                        }
                        else if (guide.equals("LEFT")) {
                            speakFlush("왼쪽으로 가세요.");
                        }
                        else if (guide.equals("STRIGHT")) {
                            speakFlush("직진하세요.");
                        }
                        else if (guide.equals("REACH")) {
                            speakFlush("곧 횡단보도가 끝납니다.");
                        }
                    }
                    Thread.sleep(500);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                log("interrupted");
            }

            return null;
        }
    }

    class doGetPictureAndProcess extends AsyncTask<String, String, String> {
        public doGetPictureAndProcess() {
        }

        @Override
        protected String doInBackground(String... params) {
            try {
                log("");
                log("start");
                playSound(soundUi);
                // 소켓 얻기
                mBtSocket = mBtDevice.createRfcommSocketToServiceRecord(uuid);

                // 데이터 버퍼
                byte[] b = new byte[PLEN];
                int len = 0;

                // 기기에 연결
                mBtSocket.connect();
                log("connected!");
                mInput = mBtSocket.getInputStream();
                mOutput = mBtSocket.getOutputStream();

                String filename = "img.jpg";

                // PHOTO 요청 전송
                mOutput.write("PHOTO".getBytes());
                len = mInput.read(b, 0, PLEN);
                int filelen = Integer.parseInt(new String(b, 0, len));
                log("filelen: " + filelen);

                // 셔터음
                playSound(soundShutter);

                // 소켓 버퍼 비우기
                while (mInput.available() != 0)
                    mInput.skip(1024);

                FileOutputStream fos
                        = openFileOutput(filename, MODE_PRIVATE);

                // 수신 준비 완료
                mOutput.write("READY".getBytes());
                int received = 0;

                // 파일 끝까지 수신
                while (received < filelen) {
                    len = mInput.read(b, 0, PLEN);
                    fos.write(b, 0, len);
                    //log("Received: " + len);
                    received += len;
                }
                fos.close();
                log("done: " + received);

                // 소켓 닫기
                mBtSocket.close();

                // 받은 이미지 처리
                FileInputStream fis = openFileInput(filename);
                mBitmap = BitmapFactory.decodeStream(fis);

                // 화면 표시
                handler.sendMessage(handler.obtainMessage(SET_IMG, mBitmap));

                // API CALL
                doDescribe();

            } catch (IOException e) {
                e.printStackTrace();
                log(e.toString());
                inProcessing = false;
            }

            return null;
        }
    }

    //////////////////////////////// Vision API ///////////////////////////////////////////

    public void doDescribe() {
        log("Describing...");

        try {
            new doRequest().execute();
        } catch (Exception e)
        {
            log("Error encountered. Exception is: " + e.toString());
        }
    }

    private String process() throws VisionServiceException, IOException {
        Gson gson = new Gson();

        // Put the image into an input stream for detection.
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        mBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(output.toByteArray());

        AnalysisResult v = this.client.describe(inputStream, 1);

        String result = gson.toJson(v);
        Log.d("result", result);

        return result;
    }

    private class doRequest extends AsyncTask<String, String, String> {
        // Store error message
        private Exception e = null;

        // for Translate
        private final static String POINT = "https://translation.googleapis.com/language/translate/v2?key=";
        private final static String KEY = "AIzaSyDydRImL1BJLdG_StTjuq-cl579ldKj-eg";
        private final static String TARGET = "&target=ko";
        private final static String SOURCE = "&source=en";
        private final static String QUERY = "&q=";

        public doRequest() {
        }

        @Override
        protected String doInBackground(String... args) {
            try {
                String data = process();
                // Display based on error existence

                log("");
                if (e != null) {
                    log("Error: " + e.getMessage());
                    this.e = null;
                } else {
                    Gson gson = new Gson();
                    AnalysisResult result = gson.fromJson(data, AnalysisResult.class);

                    for (Caption caption: result.description.captions) {
                        log("Caption: " + caption.text + "\n\tconfidence: " + caption.confidence);
                        String toSpeak = "";
                        if (caption.confidence < 0.5)
                            toSpeak += "아마도 ";
                        toSpeak += translate(caption.text) + "입니다.";
                        speakAdd(toSpeak);
                    }
                }
                inProcessing = false;

            } catch (Exception e) {
                this.e = e;    // Store error
            }

            return null;
        }

        private String translate(String str) {
            String engString = str;
            StringBuilder result = new StringBuilder();
            String korString = "";

            try {
                String encodedText = URLEncoder.encode(engString, "UTF-8");
                URL url = new URL(POINT + KEY + SOURCE + TARGET + QUERY + encodedText);
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                InputStream stream;
                if (conn.getResponseCode() == 200) {
                    stream = conn.getInputStream();
                } else {
                    stream = conn.getErrorStream();
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

                String line;

                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }

                JSONObject jsonObject = new JSONObject(result.toString());
                korString = jsonObject.getJSONObject("data")
                        .getJSONArray("translations")
                        .getJSONObject(0)
                        .getString("translatedText");
                log(korString);

            } catch (Exception e) {
                e.printStackTrace();
            }

            return korString;
        }
    }
}
