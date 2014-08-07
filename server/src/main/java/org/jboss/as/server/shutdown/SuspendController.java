package org.jboss.as.server.shutdown;

import org.jboss.as.server.logging.ServerLogger;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * The graceful shutdown controller. This class co-ordinates the graceful shutdown and pause/resume of a
 * servers operations.
 * <p/>
 * <p/>
 * In most cases this work is delegated to the {@link org.jboss.as.server.requestcontroller.GlobalRequestController},
 * however for workflows that do no correspond directly to a request model a {@link ServerActivity} instance
 * can be registered directly with this controller.
 *
 * @author Stuart Douglas
 */
public class SuspendController implements Service<SuspendController> {

    public static final ServiceName SERVICE_NAME = ServiceName.JBOSS.append("server", "suspend-controller");

    /**
     * Timer that handles the timeout. We create it on pause, rather than leaving it hanging round.
     */
    private Timer timer;

    private State state = State.RUNNING;

    private final List<ServerActivity> activities = new ArrayList<>();

    private final List<OperationListener> operationListeners = new ArrayList<>();

    private int outstandingCount;

    private final ServerActivityListener listener = new ServerActivityListener() {
        @Override
        public void requestsComplete() {
            activityPaused();
        }

        @Override
        public void unPaused() {
            activityResumed();
        }
    };


    public synchronized void suspend(long timeoutMillis) {
        ServerLogger.ROOT_LOGGER.suspendingServer();
        state = State.PAUSING;
        //we iterate a copy, in case a listener tries to register a new listener
        for(OperationListener listener: new ArrayList<>(operationListeners)) {
            listener.suspendStarted();
        }
        outstandingCount = activities.size();
        if (outstandingCount == 0) {
            handlePause();
        } else {
            for (ServerActivity activity : activities) {
                activity.pause(this.listener);
            }
            timer = new Timer();
            if (timeoutMillis > 0) {
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        timeout();
                    }
                }, timeoutMillis);
            }
        }
    }

    public synchronized void resume() {
        if (state == State.RUNNING) {
            return;
        }
        ServerLogger.ROOT_LOGGER.resumingServer();
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        for(OperationListener listener: new ArrayList<>(operationListeners)) {
            listener.cancelled();
        }
        for (ServerActivity activity : activities) {
            activity.resume();
        }
        state = State.RUNNING;
    }

    public synchronized void registerActivity(final ServerActivity activity) {
        this.activities.add(activity);
    }

    public synchronized void unRegisterActivity(final ServerActivity activity) {
        this.activities.remove(activity);
    }

    @Override
    public synchronized void start(StartContext startContext) throws StartException {
        state = State.RUNNING;
    }

    @Override
    public synchronized void stop(StopContext stopContext) {
    }

    synchronized void activityPaused() {
        --outstandingCount;
        handlePause();
    }

    private void handlePause() {
        state = State.PAUSED;
        if (outstandingCount == 0) {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }

            for(OperationListener listener: new ArrayList<>(operationListeners)) {
                listener.complete();
            }
        }
    }

    private synchronized void activityResumed() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        for(OperationListener listener: new ArrayList<>(operationListeners)) {
            listener.cancelled();
        }
    }

    synchronized void timeout() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        for(OperationListener listener: new ArrayList<>(operationListeners)) {
            listener.timeout();
        }
    }


    public synchronized void addListener(final OperationListener listener) {
        operationListeners.add(listener);
    }

    public synchronized void removeListener(final OperationListener listener) {
        operationListeners.remove(listener);
    }

    @Override
    public SuspendController getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    public static enum State {
        RUNNING,
        PAUSING,
        PAUSED
    }
}