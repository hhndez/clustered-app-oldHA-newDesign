package com.solacesystems.poc.model;

import com.solacesystems.poc.conn.Helper;
import com.solacesystems.poc.conn.SolaceConnector;
import com.solacesystems.solclientj.core.SolEnum;
import com.solacesystems.solclientj.core.SolclientException;
import com.solacesystems.solclientj.core.event.*;
import com.solacesystems.solclientj.core.handle.*;

import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Primary clustering logic performed here. This class connects to a Solace Exclusive Queue
 * and related Last-Value Queue to participate in a cluster of Hot/Hot event handling application
 * instances. This class tracks the following state:
 * - HA State: whether the instance is Active or Backup; both instances are responsible for
 *             processing input and tracking application state per input, but Active members
 *             are responsible for outputting that state to downstream consumers and the LVQ.
 *
 * - Sequence State: where the application instance is with respect to processing the input
 *                   stream. For example, if an instance joins the cluster late, it reads
 *                   the value from the LVQ, synchronizes to that state, then begins consuming
 *                   messages from it's application queue. As it processes input, it's
 *                   Sequence State is "RECOVERING_FROM_FLOW until the input stream catches
 *                   up with the last known output state. Then the Sequence State changes
 *                   to UPTODATE
 *
 * - Last known output state from the application
 *
 * - Last known input state to the application
 *
 * @param <InputType> -- input message type; must extend Ordered to ensure a sequence number is present
 * @param <OutputType>-- output message type; must also extend Ordered to ensure a sequence number is present
 */
public class ClusterConnector<InputType extends Ordered, OutputType extends Ordered> {

    ////////////////////////////////////////////////////////////////////////
    //////////            Public Interface                         /////////
    ////////////////////////////////////////////////////////////////////////

    public ClusterConnector(ClusterModel<InputType, OutputType> model,
                            ClusteredAppSerializer<InputType, OutputType> serializer) {
        _model = model;
        _serializer = serializer;
        _connector = new SolaceConnector();
        initState();
    }

    public void Connect(String host, String vpn, String user, String pass, String clientName) throws SolclientException {
        _connector.ConnectSession(host, vpn, user, pass, clientName,
                new SessionEventCallback() {
                    public void onEvent(SessionHandle sessionHandle) {
                        onSessionEvent(sessionHandle.getSessionEvent());
                    }
                });
    }

    public void BindQueues(String queue, String lvq) {
        // Wait until the Solace Session is UP before binding to queues
        boolean connected = false;
        while(!connected) {
            if (_model.GetHAStatus() == HAState.CONNECTED) {
                // The order of instantiation matters; lvqflow is used for active-flow-ind
                // which triggers recovering state via browser, then starts appflow
                // after recovery completes
                _lvqBrowser = _connector.BrowseQueue(lvq,
                        new MessageCallback() {
                            public void onMessage(Handle handle) {
                                MessageSupport ms = (MessageSupport) handle;
                                onLVQMessage(ms.getRxMessage());
                            }
                        },
                        new FlowEventCallback() {
                            public void onEvent(FlowHandle flowHandle) {
                                FlowEvent event = flowHandle.getFlowEvent();
                                System.out.println("LVQ BROWSER FLOW EVENT: " + event);
                            }
                        });
                _appflow = _connector.BindQueue(queue,
                        new MessageCallback() {
                            public void onMessage(Handle handle) {
                                MessageSupport ms = (MessageSupport) handle;
                                onAppMessage(ms.getRxMessage());
                            }
                        },
                        new FlowEventCallback() {
                            public void onEvent(FlowHandle flowHandle) {
                                FlowEvent event = flowHandle.getFlowEvent();
                                onAppFlowEvent(event);
                            }
                        });
                _lvqflow = _connector.BindQueue(lvq,
                        new MessageCallback() {
                            public void onMessage(Handle handle) {
                                System.out.println("!!! ERROR !!! ONLY FOR ACTIVE-FLOW-INDICATOR; DO NOT CONSUME MESSAGES HERE !!!");
                            }
                        },
                        new FlowEventCallback() {
                            public void onEvent(FlowHandle flowHandle) {
                                onLVQFlowEvent(flowHandle);
                            }
                        });
                _model.SetSequenceStatus(SeqState.BOUND);
                connected = true;
            }
        }
    }

    public void SendOutput(ByteBuffer output, String topic) {
        // HACK: just wanted to have a nice, standalone web-gui to display these
        _connector.SendOutput(output, topic);
    }

    public void SendOutput(OutputType output, String topic) {
        // If we're the active member of the cluster, we are responsible
        // for all output but don't publish until we have new input data
        if (_model.GetHAStatus() == HAState.ACTIVE && _model.GetSequenceStatus() == SeqState.UPTODATE)
        {
            _connector.SendOutput(_serializer.SerializeOutput(output), topic);
        }
    }

    public void Destroy() {
        if (_appflow != null) {
            _appflow.stop();
            Helper.destroyHandle(_appflow);
        }
        if (_lvqBrowser != null) {
            _lvqBrowser.stop();
            Helper.destroyHandle(_lvqBrowser);
        }
        if (_lvqflow != null) {
            _lvqflow.stop();
            Helper.destroyHandle(_lvqflow);
        }
        _connector.destroy();
    }

    ////////////////////////////////////////////////////////////////////////
    //////////            Event Handlers                           /////////
    ////////////////////////////////////////////////////////////////////////

    /**
     * Invoked on the Solace session; this is used to indicate when the
     * connection is UP/Down or reconnecting
     *
     * @param event -- the Solace session connectivity event
     */
    private void onSessionEvent(SessionEvent event) {
        switch(event.getSessionEventCode()) {
            case SolEnum.SessionEventCode.UP_NOTICE:
                _model.SetHAStatus(HAState.CONNECTED);
                _model.SetSequenceStatus(SeqState.CONNECTED);
                break;
            case SolEnum.SessionEventCode.DOWN_ERROR:
                break;
            case SolEnum.SessionEventCode.RECONNECTING_NOTICE:
                break;
            case SolEnum.SessionEventCode.RECONNECTED_NOTICE:
                break;
            default:
                break;
        }
    }

    /**
     * Invoked on the application queue flow object when a flow event occurs
     *
     * @param event -- the flow object for the application queue
     */
    private void onAppFlowEvent(FlowEvent event) {
        // System.out.println("Input flow event: " + event);
    }

    /**
     * Invoked on the appflow when an app queue message arrives
     *
     * @param msg -- new solace message from the application queue
     */
    private void onAppMessage(MessageHandle msg) {
        processInputMsg(_serializer.DeserializeInput(msg));
    }

    /**
     * Invoked on the lvqflow when flow event occurs; this is used
     * to indicate which instance in the cluster is Active
     *
     * @param flowHandle -- the flow object for the LVQ
     */
    private void onLVQFlowEvent(FlowHandle flowHandle) {
        FlowEvent event = flowHandle.getFlowEvent();
        System.out.println("LVQ flow event: " + event);
        switch (event.getFlowEventEnum())
        {
            case SolEnum.FlowEventCode.UP_NOTICE:
                recoverLastState();
                break;
            case SolEnum.FlowEventCode.ACTIVE:
                becomeActive();
                break;
            case SolEnum.FlowEventCode.INACTIVE:
                becomeBackup();
                break;
            default:
                break;
        }
    }

    /**
     * Invoked on the LVQBrowser flowhandle
     *
     * @param msg -- solace msg read from the LVQ
     */
    private void onLVQMessage(MessageHandle msg) {
        processOutputMsg(_serializer.DeserializeOutput(msg));
    }

    ////////////////////////////////////////////////////////////////////////
    //////////          State Transitions                          /////////
    ////////////////////////////////////////////////////////////////////////
    private void initState() {
        _model.SetLastOutput(null);
        _model.SetHAStatus(HAState.DISCONNECTED);
        _model.SetSequenceStatus(SeqState.INIT);
    }

    /**
     * Invoked on the LVQBrowser flowhandle when message arrives
     *
     * @param lvqState -- a message from the LVQ read as port of the recovery process
     */
    private void processOutputMsg(OutputType lvqState) {
        _task.cancel();
        _lvqBrowser.stop();
        // Compare the lvq-message sequenceId to our current-state sequenceId
        OutputType curState = _model.GetLastOutput();
        String lvqstr = (lvqState==null) ? "(null)" : lvqState.toString();
        String appstr = (curState==null) ? "(null)" : curState.toString();
        System.out.println("LAST OUTPUT ID: {"+lvqstr+"}; CUR OUT ID: {"+appstr+"}");
        if (lvqState != null && (curState == null ||  curState.getSequenceId() < lvqState.getSequenceId()))
        {
            _model.SetLastOutput(lvqState);
            _model.SetSequenceStatus(SeqState.RECOVERING_FROM_FLOW);
        }
        else
        {
            _model.SetSequenceStatus(SeqState.UPTODATE);
        }
        _appflow.start();
    }

    /**
     * Invoked on the appflow when an application message arrives. If
     * the current position in the application sequence is up to dote
     * with the last output, then this function calls the application
     * listener to give it a chance to update its current application
     * state and output something representing that state.
     *
     * @param input -- new applicadtion input message
     */
    private void processInputMsg(InputType input) {
        OutputType appState = _model.GetLastOutput();
        if (appState == null || input.getSequenceId() >= appState.getSequenceId()) {
            if (_model.GetSequenceStatus() != SeqState.UPTODATE)
                _model.SetSequenceStatus(SeqState.UPTODATE);
            // Construct a new app state
            _model.UpdateApplicationState(input);
        }
        else {
            System.out.println("\tIGNORED MESSAGE {"+input.getSequenceId()
                    +"} because it is behind recovered state {"+appState.getSequenceId()+"}");
            _model.SetLastInput(input);
        }
    }

    /**
     * Invoked on the lvqflow when flow UP event occurs or when flow changes
     * from INACTIVE to ACTIVE This function tries to browse the message on
     * the LVQ to recover the last output state from this application
     */
    private void recoverLastState() {
        if (_model.GetSequenceStatus() != SeqState.RECOVERING)
        {
            System.out.println("Recovering last state from the LVQ, current sequence state is "
                    + _model.GetSequenceStatus());
            _model.SetSequenceStatus(SeqState.RECOVERING);
            _task = new TimerTask() {
                @Override
                public void run() { noLastStateMessage(); }
            };
            _timer.schedule(_task, 250);
            if (_lvqBrowser == null) {
                System.out.println("Still constructing lvq browser handle");
            }
            _lvqBrowser.start(); // if a msg arrives it is passed to processLastOutputMsg (below)
        }
        else
        {
            System.out.println("In the midst of recovering from last state, skipping the LVQ check.");
        }
        _model.SetHAStatus(HAState.BACKUP);
    }

    /**
     * After the lvqflow UP event occurs, the browser flow is started and
     * a timer set in case there are no LVQ messages to browse. In this case,
     * we timed out with no messages so we give up on the LVQ and start the
     * appflow messages from scratch.
     */
    private void noLastStateMessage() {
        _lvqBrowser.stop();
        _model.SetSequenceStatus(SeqState.RECOVERING_FROM_FLOW);
        // TBD: DO WE SET OUTPUT STATE TO NULL?
        _appflow.start();
    }

    /**
     * Invoked on the lvqflow when flow ACTIVE event occurs
     */
    private void becomeActive()
    {
        recoverLastState();
        _model.SetHAStatus(HAState.ACTIVE);
    }

    /**
     * Invoked on the lvqflow when flow INACTIVE event occurs
     */
    private void becomeBackup()
    {
        _model.SetHAStatus(HAState.BACKUP);
    }


    private final SolaceConnector _connector;
    private final ClusterModel<InputType,OutputType> _model;
    private final ClusteredAppSerializer<InputType, OutputType> _serializer;

    private FlowHandle _lvqBrowser;
    private FlowHandle _lvqflow;
    private FlowHandle _appflow;
    private final Timer _timer = new Timer();
    private TimerTask _task;
}
