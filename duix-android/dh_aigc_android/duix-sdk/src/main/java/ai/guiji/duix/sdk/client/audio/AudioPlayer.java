package ai.guiji.duix.sdk.client.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import ai.guiji.duix.sdk.client.bean.AudioFrame;
import ai.guiji.duix.sdk.client.util.Logger;


public class AudioPlayer {

    private AudioTrack audioTrack;
    private PlaybackThread playbackThread;

    private int sampleRate = 16000; // 采样率
    private int channelConfig = AudioFormat.CHANNEL_OUT_MONO; // 声道配置
    private int audioFormat = AudioFormat.ENCODING_PCM_16BIT; // 音频格式
    //    int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
    private int bufferSize = 1280;       // 10ms 320

    private ConcurrentLinkedQueue<AudioFrame> mPlayQueue = new ConcurrentLinkedQueue<>();       // 播放帧

    private AudioPlayerCallback callback;

    private ByteBuffer waitNextBuffer;

    public AudioPlayer(AudioPlayerCallback callback, float volume){
        this.callback = callback;
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, // 音频流类型
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM); // 流模式
        if (volume != 1.0F){
            audioTrack.setVolume(volume);
        }
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        Logger.d("AudioPlayer init bufferSize: " + bufferSize + " minBufferSize: " + minBufferSize);
        waitNextBuffer = ByteBuffer.allocate(bufferSize);
    }

    public void setVolume(float volume){
        audioTrack.setVolume(volume);
    }

    public void startPlay(){
        stop();
        try {
            audioTrack.play();
            callback.onPlayStart();
        } catch (Exception e){
            callback.onPlayError(-1000, e.getMessage());
        }
//        Logger.e("AudioPlayer 开始播放");
        playbackThread = new PlaybackThread();
        playbackThread.start();
    }

    public void pushStart(){
        mPlayQueue.clear();
        waitNextBuffer.clear();
        waitNextBuffer.position(0);
        // [Bug fix] 每次 pushStart 时彻底重置 AudioTrack 和 PlaybackThread
        // 否则 stopPush() → pushDone() → PlaybackThread 退出后，
        // 后续 pushStart 无法恢复播放
        try {
            if (audioTrack != null) {
                // 先停止并刷新，清除内部缓冲区的残留数据
                audioTrack.stop();
                audioTrack.flush();
                // 重新进入播放状态
                audioTrack.play();
            }
        } catch (Exception e) {
            Logger.e("pushStart: audioTrack.play() failed: " + e.getMessage());
            // [Bug fix] 如果 AudioTrack 状态异常无法恢复，重建它
            try {
                if (audioTrack != null) {
                    audioTrack.release();
                }
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                        sampleRate, channelConfig, audioFormat, bufferSize,
                        AudioTrack.MODE_STREAM);
                audioTrack.play();
                Logger.i("pushStart: AudioTrack 已重建");
            } catch (Exception e2) {
                Logger.e("pushStart: AudioTrack 重建失败: " + e2.getMessage());
            }
        }
        if (playbackThread != null) {
            playbackThread.stopPlay();
            try {
                playbackThread.join(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            playbackThread = null;
        }
        playbackThread = new PlaybackThread();
        playbackThread.start();
    }

    public void pushData(ByteBuffer data){
        while (data.hasRemaining()){
            int min = Math.min(waitNextBuffer.remaining(), data.remaining());
            byte[] b = new byte[min];
            data.get(b);
            waitNextBuffer.put(b);
            if (!waitNextBuffer.hasRemaining()){
                waitNextBuffer.position(0);
                byte[] pushBytes = new byte[waitNextBuffer.remaining()];
                waitNextBuffer.get(pushBytes);
                waitNextBuffer.position(0);
                mPlayQueue.add(new AudioFrame(pushBytes, pushBytes.length));
            }
        }
    }

    public void pushDone(){
        int size = waitNextBuffer.position();
        if (size > 0){
            waitNextBuffer.position(0);
            byte[] pushBytes = new byte[waitNextBuffer.remaining()];
            waitNextBuffer.get(pushBytes);
            waitNextBuffer.position(0);
            mPlayQueue.add(new AudioFrame(pushBytes, size));
        }
        mPlayQueue.add(new AudioFrame(true));
    }

    public void stop() {
        if (playbackThread != null) {
            try {
                playbackThread.stopPlay();
                playbackThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            playbackThread = null;
        }
        if (audioTrack != null) {
            audioTrack.stop();
        }
    }

    public void release(){
        stop();
        if (audioTrack != null) {
            audioTrack.release();
        }
        mPlayQueue.clear();
    }

    public int getPlayIndex(){
        long framesPlayed = audioTrack.getPlaybackHeadPosition();
        int durationInMillis = (int)((framesPlayed * 1000L) / audioTrack.getSampleRate());
        return durationInMillis / 40;
    }

    private class PlaybackThread extends Thread {

        private volatile boolean isPlaying = true;
        private final Object mPlayingFence = new Object();        // 给isPlaying加一个对象锁

        public void stopPlay(){
            synchronized (mPlayingFence){
                isPlaying = false;
            }
        }

        private boolean isPlaying(){
            synchronized (mPlayingFence){
                return isPlaying;
            }
        }

        @Override
        public void run() {
            super.run();
            while (isPlaying()) {
                AudioFrame top = mPlayQueue.poll();
                if (top != null){
                    if (top.completeEmptyFrame){
                        callback.onPlayEnd();
                        stopPlay();
                        break;
                    } else {
                        try {
                            audioTrack.write(top.buffer, 0, top.size, AudioTrack.WRITE_BLOCKING);
                        } catch (Exception e) {
                            Logger.e("PlaybackThread write failed: " + e.getMessage());
                        }
                    }
                }
            }
            // [Bug fix] 不在 PlaybackThread 退出时调用 audioTrack.stop()
            // 因为 pushStart() 会统一管理 AudioTrack 状态（stop + flush + play）
            // 如果这里 stop 了，可能与 pushStart 中的 play() 竞态
            // 音频数据的播放由 pushStart 中的 flush 清理
            Logger.i("AudioPlayer play finish");
        }
    }

    public interface AudioPlayerCallback{
        void onPlayStart();
        void onPlayEnd();
        void onPlayError(int code, String msg);
    }

}
