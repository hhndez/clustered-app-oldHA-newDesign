package com.solacesystems.poc;

import com.solacesystems.poc.model.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SampleClusteredApp implements ClusterEventListener<ClientOrder, AppState> {
    public static void main(String[] args) {
        if (args.length < 9) {
            System.out.println("USAGE: <IP> <APP-ID> <APP-INST-#> <SOL-VPN> <SOL-USER> <SOL-PASS> <QUEUE> <LVQ> <OUT-TOPIC>\n\n\n");
            return;
        }
        String host  = args[0];
        String appId = args[1];
        int instance = Integer.parseInt(args[2]);
        String vpn   = args[3];
        String user  = args[4];
        String pass  = args[5];
        String queue = args[6];
        String lvq   = args[7];
        String topic = args[8];

        new SampleClusteredApp(appId, instance, topic)
                .Run(host, vpn, user, pass, queue, lvq);
    }

    public SampleClusteredApp(String appId, int instance, String outTopic) {
        _appId = appId;
        _instance = instance;
        _outTopic = outTopic;

        _model = new ClusterModel<ClientOrder, AppState>(this);
        _connector = new ClusterConnector<ClientOrder, AppState>(_model, new SampleAppSerializer());
    }

    public void Run(String host, String vpn, String user, String pass, String queue, String lvq) {
        _connector.Connect(host, vpn, user, pass, _appId+"_inst"+_instance);
        _connector.BindQueues(queue, lvq);

        boolean running = true;
        while (running)
        {
            try {
                Thread.sleep(1000);
            } catch(InterruptedException e) {
                e.printStackTrace();
                running = false;
            }
        }
    }

    public void OnHAStateChange(HAState oldState, HAState newState) {
        System.out.println("HA Change: " + oldState + " => " + newState);
        sendMonitorUpdate(); // HACK!
    }

    public void OnSeqStateChange(SeqState oldState, SeqState newState) {
        System.out.println("Seq Change: " + oldState + " => " + newState);
        sendMonitorUpdate(); // HACK!
    }

    public void OnInitialStateMessage(AppState initialState) {
        System.out.println("INITIALIZING TO STATE: " + initialState);
        sendMonitorUpdate(); // HACK!
    }

    public void OnApplicationMessage(ClientOrder input) {
        // Meh. Who cares; these could be just replaying from before our current state
        sendMonitorUpdate(); // HACK!
    }

    public AppState UpdateApplicationState(ClientOrder input) {
        // IMPORTANT: State change while we're up-to-date, so every input
        // represents real state changes we need to represent
        AppState output = new AppState(input.getInstrument());
        output.setSequenceId(input.getSequenceId());
        // Notify
        System.out.println(
                _appId + ":" + _instance + " STATE: " +
                " HA = ["  + _model.GetHAStatus() +
                "] SEQ = [" + _model.GetSequenceStatus() +
                "] IN = ["  + (input==null ? "(null)" : input.getSequenceId()) +
                "] OUT = [" + (output==null ? "(null)" : output.getSequenceId()) + "]");
        // I always send, let the connector worry about if I'm active or not
        _connector.SendOutput(output, _outTopic);
        sendMonitorUpdate(); // HACK!
        return output;
    }

    /// HACK: this is just here for the extra message published to the web-monitor
    private void sendMonitorUpdate() {
        if (_model.GetHAStatus() != HAState.DISCONNECTED) {
            toJSONString(jsonBuffer);
            _connector.SendOutput(jsonBuffer, "monitor/state");
        }
    }
    private int orderedSeqId(Ordered o) {
        if (o == null) return -1;
        return o.getSequenceId();
    }
    private void toJSONString(ByteBuffer buf) {
        Ordered output = _model.GetLastOutput();
        Ordered input  = _model.GetLastInput();
        String json = String.format("{ \"Instance\":%d, \"HAState\":\"%s\", \"SeqState\":\"%s\", \"LastInput\":%d, \"LastOutput\":%d }",
                _instance, _model.GetHAStatus(), _model.GetSequenceStatus(), orderedSeqId(input), orderedSeqId(output)
        );
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN)
                .put(json.getBytes());
    }

    private final ClusterModel<ClientOrder,AppState> _model;
    private final ClusterConnector<ClientOrder,AppState> _connector;

    private final String _appId;
    private final int    _instance;
    private final String _outTopic;

    // HACK! Just to send messages to our external monitor
    private final ByteBuffer jsonBuffer = ByteBuffer.allocate(256);
}
