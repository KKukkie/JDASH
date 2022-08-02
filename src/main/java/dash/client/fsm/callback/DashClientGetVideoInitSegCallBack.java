package dash.client.fsm.callback;

import dash.client.DashClient;
import dash.client.handler.base.MessageType;
import dash.mpd.MpdManager;
import dash.mpd.parser.mpd.Representation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.AppInstance;
import util.fsm.StateManager;
import util.fsm.event.base.CallBack;
import util.fsm.unit.StateUnit;
import util.module.FileManager;

import java.util.concurrent.TimeUnit;

public class DashClientGetVideoInitSegCallBack extends CallBack {

    ////////////////////////////////////////////////////////////
    private static final Logger logger = LoggerFactory.getLogger(DashClientGetVideoInitSegCallBack.class);

    private static final TimeUnit timeUnit = TimeUnit.MICROSECONDS;

    private final FileManager fileManager = new FileManager();
    ////////////////////////////////////////////////////////////

    ////////////////////////////////////////////////////////////
    public DashClientGetVideoInitSegCallBack(StateManager stateManager, String name) {
        super(stateManager, name);
    }
    ////////////////////////////////////////////////////////////

    ////////////////////////////////////////////////////////////
    @Override
    public Object callBackFunc(StateUnit stateUnit) {
        if (stateUnit == null) { return null; }

        ////////////////////////////
        // GET MPD DONE > PARSE MPD & GET META DATA
        DashClient dashClient = (DashClient) stateUnit.getData();
        if (dashClient == null) { return null; }

        if (!AppInstance.getInstance().getConfigManager().isAudioOnly()) {
            MpdManager mpdManager = dashClient.getMpdManager();
            if (mpdManager != null) {
                for (Representation representation : mpdManager.getRepresentations(MpdManager.CONTENT_VIDEO_TYPE)) {
                    if (representation == null) { continue; }
                    logger.debug("VIDEO INIT CALL BACK representation: {}", representation);

                    long videoSegmentDuration = dashClient.getMpdManager().getVideoSegmentDuration(representation.getId()); // 1000000
                    if (videoSegmentDuration > 0) {
                        try {
                            timeUnit.sleep(videoSegmentDuration);
                            logger.trace("[DashClientGetVideoInitSegCallBack({})] [VIDEO({})] Waiting... ({})",
                                    dashClient.getDashUnitId(), representation.getId(), videoSegmentDuration
                            );
                        } catch (Exception e) {
                            //logger.warn("");
                        }
                    }

                    String videoSegmentName = mpdManager.getVideoMediaSegmentName(representation.getId());
                    logger.debug("[DashClientGetVideoInitSegCallBack({})] RepresentationId={}, videoSegmentName={}",
                            dashClient.getDashUnitId(), representation.getId(), videoSegmentName
                    );
                    dashClient.sendHttpGetRequest(
                            fileManager.concatFilePath(
                                    dashClient.getSrcPath(),
                                    videoSegmentName
                            ),
                            MessageType.VIDEO
                    );
                }
            }
        }
        ////////////////////////////

        return stateUnit.getCurState();
    }
    ////////////////////////////////////////////////////////////

}
