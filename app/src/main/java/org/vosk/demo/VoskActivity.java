// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.StringTokenizer;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import info.debatty.java.stringsimilarity.NormalizedLevenshtein;

public class VoskActivity extends Activity implements
        RecognitionListener {

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_FILE = 3;
    static private final int STATE_MIC = 4;

    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private Model model;
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private TextView resultView;

    String decodingFilename;
    String referenceText;

    String previousPartialResult = "";
    String partialResult = "";
    NormalizedLevenshtein l;
    int referenceTextCnt;
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        // Setup layout
        resultView = findViewById(R.id.result_text);
        setUiState(STATE_START);
        l = new NormalizedLevenshtein();
        decodingFilename = "SPK073KBSCU065M013.wav";
        String jsonFilename = decodingFilename.replaceAll(".wav", "") + ".json";
        try {
            JSONObject obj = new JSONObject(loadJSONFromAsset(jsonFilename));
            JSONObject obj2 = obj.getJSONObject("script");
            String referText = obj2.getString("text");
            referText = referText.replaceAll("[^ㄱ-ㅎㅏ-ㅣ가-힣a-zA-Z\\s]", "");
            referText = referText.trim();

            referenceText = referText + " " + referText;
            StringTokenizer st = new StringTokenizer(referenceText, " ");
            referenceTextCnt = st.countTokens() / 2;


            Log.d("json불러오기", referText);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }


        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));

        LibVosk.setLogLevel(LogLevel.INFO);

        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
    }

    public String loadJSONFromAsset(String jsonFilename){
        String json = null;
        try {
            InputStream is = getAssets().open(jsonFilename);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
        return json;
    }

    double bestScore;
    String targetText;
    public void findReadingPosition(String asrText){
        //읽을 텍스트를 분리
        String[] splitReferenceText = referenceText.split(" ");
        // 읽을 텍스트 ngram 배열 생성(크기5)
        ArrayList<String> candidateTextArray = new ArrayList<>();
        String elementText = "";
        for (String str:splitReferenceText){
            if (candidateTextArray.size() > 4){
                break;
            }
            elementText = elementText + " " + str;
            elementText = elementText.trim();
            candidateTextArray.add(elementText);
        }
//        Log.d("읽기 후보군 배열", candidateTextArray.toString());

        //읽은 텍스트 찾기
        bestScore = 0;
        targetText = "";
        for (String str:candidateTextArray){
            double score = 1 - l.distance(asrText, str);
            if (score > bestScore){
                bestScore = score;
                targetText = str;

            }
        }

        if (bestScore > 0.3){
            //읽은 텍스트가 유사도 0.3을 넘었을 때 읽어야할 전체 텍스트에서 해당 부분 삭제
            Log.d("읽기 위치 추적 결과", targetText);
            referenceText = referenceText.replaceFirst(targetText, "");
            referenceText = referenceText.trim();
        }

    }

    private void initModel() {
        StorageService.unpack(this, "model-en-us", "model",
                (model) -> {
                    this.model = model;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }

    public String excludeCommonString(String str1, String str2){
        char[] array1 = str1.toCharArray();
        char[] array2 = str2.toCharArray();

        int len1 = array1.length;
        int len2 = array2.length;

        int[][] dp = new int[len1 + 1][len2 + 1];
        int max = 0;
        for (int i = 1; i <= len1; i++){
            for (int j=1; j<= len2; j++){
                if (array1[i-1] == array2[j-1]){
                    dp[i][j] = dp[i-1][j-1] + 1;
                    max = Math.max(max, dp[i][j]);
                }
            }
        }
        return str1.substring(max);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in async task
                initModel();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }

        if (speechStreamService != null) {
            speechStreamService.stop();
        }
    }

    @Override
    public void onResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
    }

    @Override
    public void onFinalResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
        setUiState(STATE_DONE);
        if (speechStreamService != null) {
            speechStreamService = null;
        }

        Log.d("읽어야할 어절 수", String.valueOf(referenceTextCnt));
        StringTokenizer st = new StringTokenizer(referenceText, " ");
        int readCnt = st.countTokens();
//        Log.d("읽고 남은 어절 수", String.valueOf(readCnt));
        int differenceCnt = Math.abs(referenceTextCnt - readCnt);
        Log.d("오차", String.valueOf(differenceCnt));
    }

    @Override
    public void onPartialResult(String hypothesis) {
        resultView.append(hypothesis + "\n");

        hypothesis = hypothesis.replaceFirst("partial","");
        hypothesis = hypothesis.replaceAll("[^ㄱ-ㅎㅏ-ㅣ가-힣a-zA-Z0-9\\s]", "");
        hypothesis = hypothesis.trim();


        if (!hypothesis.equals("") && !previousPartialResult.equals("")){

            if (hypothesis.contains(previousPartialResult)){
                partialResult = hypothesis.replaceFirst(previousPartialResult, "");
            } else {
                partialResult = excludeCommonString(hypothesis, previousPartialResult);

            }
            partialResult = partialResult.trim();
            if (!partialResult.equals("")){
                Log.d("음성인식 결과", partialResult);
                findReadingPosition(partialResult);
            }

        } else {
            if (!hypothesis.equals("")){
                Log.d("음성인식 결과", hypothesis);
                findReadingPosition(hypothesis);
            }
        }


        previousPartialResult = hypothesis;




    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                ((ToggleButton) findViewById(R.id.pause)).setChecked(false);
                break;
            case STATE_FILE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.stop_file);
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((true));
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_file).setEnabled(false);
        findViewById(R.id.recognize_mic).setEnabled(false);
    }

    private void recognizeFile() {
        if (speechStreamService != null) {
            setUiState(STATE_DONE);
            speechStreamService.stop();
            speechStreamService = null;
        } else {
            setUiState(STATE_FILE);
            try {
//                Recognizer rec = new Recognizer(model, 16000.f, "[\"one zero zero zero one\", " +
//                        "\"oh zero one two three four five six seven eight nine\", \"[unk]\"]");

                Recognizer rec = new Recognizer(model, 44100.f);

                InputStream ais = getAssets().open(decodingFilename);
                if (ais.skip(44) != 44) throw new IOException("File too short");

                speechStreamService = new SpeechStreamService(rec, ais, 44100);
                speechStreamService.start(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC);
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }


    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }

}
