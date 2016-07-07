package com.solacesystems.poc.model;

/**
 * Stores all the state relevant to the cluster member instance include HA state,
 * Sequencing state, and last input/output state messages
 *
 * The cluster model also updates a ClusterEventListener on all state changes.
 *
 * @param <InputType> -- input message type; must extend Ordered to ensure a sequence number is present
 * @param <OutputType>-- output message type; must also extend Ordered to ensure a sequence number is present
 */
public class ClusterModel<InputType extends Ordered, OutputType extends Ordered> {
    public ClusterModel(ClusterEventListener<InputType,OutputType> listener) {
        _listener = listener;
        _haStatus = HAState.DISCONNECTED;
        _seqStatus= SeqState.INIT;
    }

    public HAState GetHAStatus() {
        return _haStatus;
    }
    public void SetHAStatus(HAState haStatus) {
        HAState old = _haStatus;
        _haStatus = haStatus;
        _listener.OnHAStateChange(old, haStatus);
    }

    public SeqState GetSequenceStatus() {
        return _seqStatus;
    }
    public void SetSequenceStatus(SeqState seqStatus) {
        SeqState old = seqStatus;
        _seqStatus = seqStatus;
        _listener.OnSeqStateChange(old, seqStatus);
    }

    public InputType GetLastInput() {
        return _lastInput;
    }
    public void SetLastInput(InputType lastInput) {
        _lastInput = lastInput;
        _listener.OnApplicationMessage(lastInput);
    }

    public OutputType GetLastOutput() {
        return _lastOutput;
    }
    public void SetLastOutput(OutputType lastOutput) {
        _lastOutput = lastOutput;
        _listener.OnInitialStateMessage(lastOutput);
    }

    /**
     * This is an important variation of SetLastInput where the
     * ClusterConnector knows that the cluster instance is up-to-date,
     * so every input requires an updated state output
     *
     * @param input -- the input message driving a potential application state change
     */
    public void UpdateApplicationState(InputType input) {
        _lastOutput = _listener.UpdateApplicationState(input);
        _lastInput = input;
    }

    @Override
    public String toString() {
        return  "] HA = ["  + _haStatus +
                "] SEQ = [" + _seqStatus +
                "] IN = ["  + (_lastInput==null ? "(null)" : _lastInput.getSequenceId()) +
                "] OUT = [" + (_lastOutput==null ? "(null)" : _lastOutput.getSequenceId()) + "]";
    }

    private HAState _haStatus;
    private SeqState _seqStatus;

    private InputType _lastInput;
    private OutputType _lastOutput;

    private final ClusterEventListener<InputType,OutputType> _listener;
}
