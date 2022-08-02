package dash.client.handler.video;

import dash.client.DashClient;
import dash.client.fsm.DashClientEvent;
import dash.client.fsm.DashClientFsmManager;
import dash.client.fsm.DashClientState;
import dash.client.handler.base.DashHttpMessageHandler;
import dash.client.handler.base.MessageType;
import dash.mpd.MpdManager;
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

import java.util.concurrent.TimeUnit;

public class DashVideoHttpMessageHandler extends DashHttpMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(DashVideoHttpMessageHandler.class);

    private static final TimeUnit timeUnit = TimeUnit.MICROSECONDS;

    private final int retryCount;

    private final DashClient dashClient;
    private final FileManager fileManager = new FileManager();

    private String representationId;

    public DashVideoHttpMessageHandler(DashClient dashClient) {
        this.dashClient = dashClient;
        this.retryCount = AppInstance.getInstance().getConfigManager().getDownloadChunkRetryCount();
    }

    @Override
    public void processContent(HttpObject httpObject, ChannelHandlerContext channelHandlerContext) {
        if (httpObject instanceof HttpResponse) {
            HttpResponse httpResponse = (HttpResponse) httpObject;

            dashClient.stopVideoTimeout();
            if (!httpResponse.status().equals(HttpResponseStatus.OK)) {
                // 재시도 로직
                if (!retry()) {
                    logger.warn("[DashVideoHttpClientHandler({})] [-] [VIDEO] !!! RECV NOT OK. DashClient will be stopped. (status={}, retryCount={})",
                            dashClient.getDashUnitId(), httpResponse.status(), retryCount
                    );
                    finish(channelHandlerContext);
                }
                return;
            } else {
                int videoRetryCount = dashClient.getVideoRetryCount();
                if (videoRetryCount > 0) {
                    dashClient.setVideoRetryCount(0);
                    dashClient.setVideoCompensationTime(0);
                    dashClient.setIsVideoRetrying(false);
                }
            }

            printHeader(httpResponse);
        }
    }

    @Override
    public void processResponse(HttpObject httpObject, ChannelHandlerContext channelHandlerContext) {
        if (httpObject instanceof HttpContent) {
            if (dashClient.isVideoRetrying()) { return; }

            HttpContent httpContent = (HttpContent) httpObject;
            ByteBuf buf = httpContent.content();
            if (buf == null) {
                logger.warn("[DashVideoHttpClientHandler({})] DatagramPacket's content is null.", dashClient.getDashUnitId());
                finish(channelHandlerContext);
                return;
            }

            int readBytes = buf.readableBytes();
            if (buf.readableBytes() <= 0) {
                logger.warn("[DashVideoHttpClientHandler({})] Message is null.", dashClient.getDashUnitId());
                finish(channelHandlerContext);
                return;
            }

            byte[] data = new byte[readBytes];
            buf.getBytes(0, data);

            // TODO : Handle multiple representations
            this.representationId = dashClient.getMpdManager().getFirstRepresentationId(MpdManager.CONTENT_VIDEO_TYPE);
            long videoSegmentSeqNum = dashClient.getMpdManager().getVideoSegmentSeqNum(representationId);
            String curVideoSegmentName = dashClient.getMpdManager().getVideoMediaSegmentName(representationId);
            if (curVideoSegmentName == null) {
                logger.warn("[DashVideoHttpClientHandler({})] [+] [VIDEO] MediaSegment name is not defined. (videoSeqNum={})",
                        dashClient.getDashUnitId(), dashClient.getMpdManager().getVideoSegmentSeqNum(representationId)
                );
                finish(channelHandlerContext);
                return;
            }

            // VIDEO FSM
            DashClientFsmManager dashClientVideoFsmManager = dashClient.getDashClientVideoFsmManager();
            if (dashClientVideoFsmManager == null) {
                logger.warn("[DashAudioHttpClientHandler({})] Video Fsm manager is not defined.", dashClient.getDashUnitId());
                finish(channelHandlerContext);
                return;
            }

            StateManager videoStateManager = dashClientVideoFsmManager.getStateManager();
            StateHandler videoStateHandler = videoStateManager.getStateHandler(DashClientState.NAME);
            StateUnit videoStateUnit = videoStateManager.getStateUnit(dashClient.getDashClientStateUnitId());
            String curVideoState = videoStateUnit.getCurState();
            switch (curVideoState) {
                case DashClientState.MPD_DONE:
                    dashClient.getMpdManager().makeInitSegment(fileManager, dashClient.getTargetVideoInitSegPath(), data);
                    break;
                case DashClientState.VIDEO_INIT_SEG_DONE:
                    String targetVideoMediaSegPath = fileManager.concatFilePath(
                            dashClient.getTargetBasePath(),
                            curVideoSegmentName
                    );
                    dashClient.getMpdManager().makeMediaSegment(fileManager, targetVideoMediaSegPath, data);

                    MediaSegmentController videoSegmentController = dashClient.getVideoSegmentController();
                    if (videoSegmentController != null) {
                        videoSegmentController.getMediaSegmentInfo().setLastSegmentNumber(videoSegmentSeqNum);
                    }
                    break;
                default:
                    break;
            }

            //logger.trace("[DashVideoHttpClientHandler({})] [VIDEO] {}", dashClient.getDashUnitId(), data);
            if (httpContent instanceof LastHttpContent) {
                switch (curVideoState) {
                    case DashClientState.MPD_DONE:
                        videoStateHandler.fire(DashClientEvent.GET_VIDEO_INIT_SEG, videoStateUnit);
                        break;
                    case DashClientState.VIDEO_INIT_SEG_DONE:
                        sendReqForSegment(channelHandlerContext, true);
                        break;
                    default:
                        break;
                }

                logger.trace("[DashVideoHttpClientHandler({})] } END OF CONTENT <", dashClient.getDashUnitId());
            }
        }
    }

    @Override
    protected void printHeader(HttpResponse httpResponse) {
        if (logger.isTraceEnabled()) {
            logger.trace("[DashVideoHttpClientHandler({})] > STATUS: {}", dashClient.getDashUnitId(), httpResponse.status());
            logger.trace("> VERSION: {}", httpResponse.protocolVersion());

            if (!httpResponse.headers().isEmpty()) {
                for (CharSequence name : httpResponse.headers().names()) {
                    for (CharSequence value : httpResponse.headers().getAll(name)) {
                        logger.trace("[DashVideoHttpClientHandler({})] > HEADER: {} = {}", dashClient.getDashUnitId(), name, value);
                    }
                }
            }

            if (HttpHeaderUtil.isTransferEncodingChunked(httpResponse)) {
                logger.trace("[DashVideoHttpClientHandler({})] > CHUNKED CONTENT {", dashClient.getDashUnitId());
            } else {
                logger.trace("[DashVideoHttpClientHandler({})] > CONTENT {", dashClient.getDashUnitId());
            }
        }
    }

    @Override
    protected void sendReqForSegment(ChannelHandlerContext channelHandlerContext, boolean isTrySleep) {
        long curSeqNum = dashClient.getMpdManager().incAndGetVideoSegmentSeqNum(representationId);
        String newVideoSegmentName = dashClient.getMpdManager().getVideoMediaSegmentName(representationId);
        if (newVideoSegmentName == null) {
            logger.warn("[DashVideoHttpClientHandler({})] [+] [VIDEO] Current MediaSegment name is not defined. (videoSeqNum={})",
                    dashClient.getDashUnitId(), curSeqNum
            );
            finish(channelHandlerContext);
            return;
        }
        //logger.debug("[DashVideoHttpClientHandler({})] [+] [VIDEO] [seq={}] MediaSegment is changed. ([{}] > [{}])", dashClient.getDashUnitId(), curSeqNum, curVideoSegmentName, newVideoSegmentName);

        if (isTrySleep) {
            // SegmentDuration 만큼(micro-sec) sleep
            long segmentDuration = dashClient.getMpdManager().getVideoSegmentDuration(representationId); // 1000000
            if (segmentDuration > 0) {
                try {
                    segmentDuration = dashClient.getMpdManager().applyAtoIntoDuration(representationId, segmentDuration, MpdManager.CONTENT_VIDEO_TYPE);

                    long videoCompensationTime = dashClient.getVideoCompensationTime();
                    if (videoCompensationTime > 0) {
                        //logger.debug("videoCompensationTime: {}", videoCompensationTime);
                        long newSegmentDuration = segmentDuration - videoCompensationTime;
                        if (newSegmentDuration >= 0) {
                            segmentDuration = newSegmentDuration;
                        }
                    }

                    //logger.debug("[DashVideoHttpClientHandler({})] [VIDEO] Waiting... ({})", dashClient.getDashUnitId(), segmentDuration);
                    timeUnit.sleep(segmentDuration);
                } catch (Exception e) {
                    //logger.warn("");
                }
            }
        }

        dashClient.sendHttpGetRequest(
                fileManager.concatFilePath(
                        dashClient.getSrcPath(),
                        newVideoSegmentName
                ),
                MessageType.VIDEO
        );
    }

    @Override
    protected boolean retry() {
        int curVideoRetryCount = dashClient.incAndGetVideoRetryCount();
        if (curVideoRetryCount > retryCount) {
            dashClient.setIsVideoRetrying(false);
            return false;
        }
        dashClient.setIsVideoRetrying(true);

        long segmentDuration = dashClient.getMpdManager().getVideoSegmentDuration(representationId); // 1000000
        if (segmentDuration > 0) {
            try {
                segmentDuration = dashClient.getMpdManager().applyAtoIntoDuration(representationId, segmentDuration, MpdManager.CONTENT_VIDEO_TYPE); // 800000

                int retryIntervalFactor = retryCount - (curVideoRetryCount - 1);
                if (retryIntervalFactor <= 0) { retryIntervalFactor = 1; }
                segmentDuration /= retryIntervalFactor;

                long curVideoCompensationFactor = dashClient.getVideoCompensationTime();
                dashClient.setVideoCompensationTime(curVideoCompensationFactor + segmentDuration);

                //logger.debug("[DashVideoHttpClientHandler({})] [VIDEO] Waiting... ({})", dashClient.getDashUnitId(), segmentDuration);
                timeUnit.sleep(segmentDuration);
            } catch (Exception e) {
                //logger.warn("");
            }
        }

        String curVideoSegmentName = dashClient.getMpdManager().getVideoMediaSegmentName(representationId);
        dashClient.sendHttpGetRequest(
                fileManager.concatFilePath(
                        dashClient.getSrcPath(),
                        curVideoSegmentName
                ),
                MessageType.VIDEO
        );

        //logger.warn("[DashVideoHttpClientHandler({})] [VIDEO] [count={}] Retrying... ({})", dashClient.getDashUnitId(), curVideoRetryCount, curVideoSegmentName);
        return true;
    }

    @Override
    protected void finish(ChannelHandlerContext channelHandlerContext) {
        DashUnit dashUnit = ServiceManager.getInstance().getDashServer().getDashUnitById(dashClient.getDashUnitId());
        if (dashUnit != null) {
            if (dashUnit.getType().equals(StreamType.STATIC)) {
                dashClient.stop();
            } else {
                ServiceManager.getInstance().getDashServer().deleteDashUnit(dashClient.getDashUnitId());
            }
        }
        channelHandlerContext.close();
    }

}
