package dash.client.handler.mpd;

import dash.client.DashClient;
import dash.client.fsm.DashClientEvent;
import dash.client.fsm.DashClientFsmManager;
import dash.client.fsm.DashClientState;
import dash.client.handler.base.DashHttpMessageHandler;
import dash.client.handler.base.MessageType;
import dash.mpd.MpdManager;
import dash.mpd.parser.mpd.Representation;
import dash.unit.DashUnit;
import dash.unit.StreamType;
import dash.unit.segment.MediaSegmentController;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.AppInstance;
import service.ServiceManager;
import util.fsm.StateManager;
import util.fsm.module.StateHandler;
import util.fsm.unit.StateUnit;
import util.module.FileManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DashMpdHttpMessageHandler extends DashHttpMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(DashMpdHttpMessageHandler.class);

    private static final TimeUnit timeUnitSec = TimeUnit.SECONDS;

    private final int retryCount;

    private final DashClient dashClient;
    private final FileManager fileManager = new FileManager();

    private final long defaultMediaPresentationDuration;

    public DashMpdHttpMessageHandler(DashClient dashClient) {
        this.dashClient = dashClient;
        this.defaultMediaPresentationDuration = AppInstance.getInstance().getConfigManager().getChunkFileDeletionWindowSize();
        this.retryCount = AppInstance.getInstance().getConfigManager().getDownloadChunkRetryCount();
    }

    @Override
    public void processContent(HttpObject httpObject, ChannelHandlerContext channelHandlerContext) {
        if (httpObject instanceof HttpContent) {
            if (dashClient.isMpdRetrying()) {
                return;
            }

            HttpContent httpContent = (HttpContent) httpObject;
            ByteBuf buf = httpContent.content();
            if (buf == null) {
                logger.warn("[PreProcessClientChannelHandler] DatagramPacket's content is null.");
                ServiceManager.getInstance().getDashServer().deleteDashUnit(dashClient.getDashUnitId());
                channelHandlerContext.close();
                return;
            }

            int readBytes = buf.readableBytes();
            if (buf.readableBytes() <= 0) {
                logger.warn("[PreProcessClientChannelHandler] Message is null. Fail to get the mpd.");
                ServiceManager.getInstance().getDashServer().deleteDashUnit(dashClient.getDashUnitId());
                channelHandlerContext.close();
                return;
            }

            byte[] data = new byte[readBytes];
            buf.getBytes(0, data);

            dashClient.getMpdManager().makeMpd(fileManager, dashClient.getTargetMpdPath(), data);

            //logger.trace("[DashMpdHttpClientHandler({})] [MPD] {}", dashClient.getDashUnitId(), data);
            if (httpContent instanceof LastHttpContent) {
                logger.trace("[DashMpdHttpClientHandler({})] } END OF CONTENT <", dashClient.getDashUnitId());

                // GET PARSE MPD & GET META DATA
                if (!parseMpd()) {
                    logger.warn("[DashMpdHttpClientHandler({})] Fail to parse the mpd. (dashClient={})", dashClient.getDashUnitId(), dashClient);
                    ServiceManager.getInstance().getDashServer().deleteDashUnit(dashClient.getDashUnitId());
                    channelHandlerContext.close();
                    return;
                }

                if (!processFsm()) {
                    logger.warn("[DashAudioHttpClientHandler({})] Audio Fsm manager is not defined.", dashClient.getDashUnitId());
                    ServiceManager.getInstance().getDashServer().deleteDashUnit(dashClient.getDashUnitId());
                    channelHandlerContext.close();
                    return;
                }

                MediaSegmentController audioSegmentController = dashClient.getAudioSegmentController();
                if (audioSegmentController != null
                        && audioSegmentController.getMediaSegmentInfo().getFirstSegmentNumber() == 0) {
                    audioSegmentController.getMediaSegmentInfo().setFirstSegmentNumber(
                            dashClient.getMpdManager().getAudioSegmentSeqNum()
                    );
                }

                MediaSegmentController videoSegmentController = dashClient.getVideoSegmentController();
                if (videoSegmentController != null
                        && videoSegmentController.getMediaSegmentInfo().getFirstSegmentNumber() == 0) {
                    videoSegmentController.getMediaSegmentInfo().setFirstSegmentNumber(
                            dashClient.getMpdManager().getVideoSegmentSeqNum()
                    );
                }

                // MPD 재요청
                requestMpdAgain();
            }
        }
    }

    @Override
    public void processResponse(HttpObject httpObject, ChannelHandlerContext channelHandlerContext) {
        if (httpObject instanceof HttpResponse) {
            HttpResponse httpResponse = (HttpResponse) httpObject;

            dashClient.stopMpdTimeout();
            if (!httpResponse.status().equals(HttpResponseStatus.OK)) {
                // 재시도 로직
                if (!retry()) {
                    logger.warn("[DashMpdHttpClientHandler({})] [-] [MPD] !!! RECV NOT OK. DashClient will be stopped. (status={})", dashClient.getDashUnitId(), httpResponse.status());
                    ServiceManager.getInstance().getDashServer().deleteDashUnit(dashClient.getDashUnitId());
                    channelHandlerContext.close();
                }
                return;
            } else {
                if (dashClient.getMpdRetryCount() > 0) {
                    dashClient.setMpdRetryCount(0);
                    dashClient.setIsMpdRetrying(false);
                }
            }

            printHeader(httpResponse);
        }
    }

    @Override
    protected void printHeader(HttpResponse httpResponse) {
        if (logger.isTraceEnabled()) {
            logger.trace("[DashMpdHttpClientHandler({})] > STATUS: {}", dashClient.getDashUnitId(), httpResponse.status());
            logger.trace("> VERSION: {}", httpResponse.protocolVersion());

            if (!httpResponse.headers().isEmpty()) {
                for (CharSequence name : httpResponse.headers().names()) {
                    for (CharSequence value : httpResponse.headers().getAll(name)) {
                        logger.trace("[DashMpdHttpClientHdler({})] > HEADER: {} = {}", dashClient.getDashUnitId(), name, value);
                    }
                }
            }

            if (HttpHeaderUtil.isTransferEncodingChunked(httpResponse)) {
                logger.trace("[DashMpdHttpClientHandler({})] > CHUNKED CONTENT {", dashClient.getDashUnitId());
            } else {
                logger.trace("[DashMpdHttpClientHandler({})] > CONTENT {", dashClient.getDashUnitId());
            }
        }
    }

    @Override
    protected void sendReqForSegment(ChannelHandlerContext channelHandlerContext, boolean isTrySleep) {
        // Not used
    }

    @Override
    protected boolean retry() {
        int curMpdRetryCount = dashClient.incAndGetMpdRetryCount();
        if (curMpdRetryCount > retryCount) {
            dashClient.setIsMpdRetrying(false);
            return false;
        }
        dashClient.setIsMpdRetrying(true);

        try {
            //logger.trace("[DashAudioHttpClientHandler({})] [MPD] Waiting... ({}sec)", dashClient.getDashUnitId(), 1);
            timeUnitSec.sleep((long) AppInstance.getInstance().getConfigManager().getRemoteTimeOffset());
        } catch (Exception e) {
            //logger.warn("");
        }

        dashClient.sendHttpGetRequest(dashClient.getSrcPath(), MessageType.MPD);
        //logger.warn("[DashMpdHttpClientHandler({})] [MPD] [count={}] Retrying... ({})", dashClient.getDashUnitId(), curMpdRetryCount, dashClient.getSrcPath());
        return true;
    }

    @Override
    protected void finish(ChannelHandlerContext channelHandlerContext) {

    }

    private boolean parseMpd() {
        if (!dashClient.getMpdManager().parseMpd(dashClient.getTargetMpdPath(), true)) {
            return false;
        }

        if (AppInstance.getInstance().getConfigManager().isEnableValidation()) {
            if (dashClient.getMpdManager().validate()) {
                logger.debug("[DashMpdHttpClientHandler({})] Success to validate the mpd. (mpdPath={})", dashClient.getDashUnitId(), dashClient.getTargetMpdPath());
            } else {
                logger.warn("[DashMpdHttpClientHandler({})] Fail to validate the mpd. (mpdPath={})", dashClient.getDashUnitId(), dashClient.getTargetMpdPath());
                DashUnit dashUnit = ServiceManager.getInstance().getDashServer().getDashUnitById(dashClient.getDashUnitId());
                if (dashUnit != null) {
                    if (dashUnit.getType().equals(StreamType.STATIC)) {
                        dashClient.stop();
                    } else {
                        ServiceManager.getInstance().getDashServer().deleteDashUnit(dashClient.getDashUnitId());
                    }
                }
                return false;
            }
        }

        dashClient.getMpdManager().setIsMpdDone(true);
        return true;
    }

    private boolean processFsm() {
        // AUDIO FSM
        DashClientFsmManager dashClientAudioFsmManager = dashClient.getDashClientAudioFsmManager();
        if (dashClientAudioFsmManager == null) {
            logger.warn("[DashMpdHttpClientHandler({})] Fail to process fsm. DashClientFsmManager is not exist. (mpdPath={})", dashClient.getDashUnitId(), dashClient.getTargetMpdPath());
            return false;
        }

        // VIDEO FSM
        DashClientFsmManager dashClientVideoFsmManager = dashClient.getDashClientVideoFsmManager();

        // FIRE TO NEXT EVENT IN DashClientAudioFsmManager
        StateManager audioStateManager = dashClientAudioFsmManager.getStateManager();
        StateHandler audioStateHandler = dashClientAudioFsmManager.getStateManager().getStateHandler(DashClientState.NAME);
        StateUnit audioStateUnit = audioStateManager.getStateUnit(dashClient.getDashClientStateUnitId());
        String curAudioState = audioStateUnit.getCurState();
        if (DashClientState.IDLE.equals(curAudioState)) {
            List<Representation> representations = dashClient.getMpdManager().getRepresentations(MpdManager.CONTENT_AUDIO_TYPE);
            for (Representation representation : representations) {
                dashClient.getMpdManager().setSegmentStartNumber(representation.getId(), MpdManager.CONTENT_AUDIO_TYPE);
            }
            audioStateHandler.fire(DashClientEvent.GET_MPD_AUDIO, audioStateUnit);
        }

        if (dashClientVideoFsmManager != null) {
            // FIRE TO NEXT EVENT IN DashClientVideoFsmManager
            StateManager videoStateManager = dashClientVideoFsmManager.getStateManager();
            StateHandler videoStateHandler = dashClientVideoFsmManager.getStateManager().getStateHandler(DashClientState.NAME);
            StateUnit videoStateUnit = videoStateManager.getStateUnit(dashClient.getDashClientStateUnitId());
            String curVideoState = videoStateUnit.getCurState();
            if (DashClientState.IDLE.equals(curVideoState)) {
                List<Representation> representations = dashClient.getMpdManager().getRepresentations(MpdManager.CONTENT_VIDEO_TYPE);
                for (Representation representation : representations) {
                    dashClient.getMpdManager().setSegmentStartNumber(representation.getId(), MpdManager.CONTENT_VIDEO_TYPE);
                }

                videoStateHandler.fire(DashClientEvent.GET_MPD_VIDEO, videoStateUnit);
            }
        }

        return true;
    }

    private void requestMpdAgain() {
        /**
         * @ mediaPresentationDuration : Refers to the duration of the media content
         *      It has 'PT' as prefix denoting that time-range is in units of seconds (S), minutes (M), hours (H) and days (D).
         *      In this scenario we have the value as "PT23M12.128S",
         *          i.e., the media content has a total duration of 23 minutes 12.128 seconds
         */
        Duration mediaPresentationDuration = dashClient.getMpdManager().getMediaPresentationDuration();
        if (mediaPresentationDuration != null) {
            try {
                long seconds = mediaPresentationDuration.getSeconds();
                if (seconds <= 0) {
                    seconds = defaultMediaPresentationDuration;
                }

                //logger.debug("[DashMpdHttpClientHandler({})] [MPD] Waiting... ({})", dashClient.getDashUnitId(), seconds);
                timeUnitSec.sleep(seconds);

                // SEND MPD REQUEST again for MediaPresentationDuration
                dashClient.sendHttpGetRequest(dashClient.getSrcPath(), MessageType.MPD);
            } catch (Exception e) {
                //logger.warn("");
            }
        }
    }

}
