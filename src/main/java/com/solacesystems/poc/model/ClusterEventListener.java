package com.solacesystems.poc.model;

/**
 * A ClusterEventListener is updated by the ClusterModel for every state
 * change related to the application cluster. Relevant state changes include:
 *
 * - HAState change: from Backup to Active or vice versaa
 * - SeqState change: what the application's state is with respect to the input stream; e.g. recovering, up-to-date, etc.
 * - Initial State message: when the LastValueQueue of the cluster has been read to provide last known state of the application cluster
 * - Application input message: when a new input message is read by the ClusterConnector
 *
 * @param <InputType> -- input message type; must extend Ordered to ensure a sequence number is present
 * @param <OutputType>-- output message type; must also extend Ordered to ensure a sequence number is present
 */
public interface ClusterEventListener<InputType extends Ordered, OutputType extends Ordered> {

    /**
     * HA State changes can include: Disconnected, Connected, Backup, Active
     *
     * @param oldState -- previous HA State
     * @param newState -- new HA State
     */
    void OnHAStateChange(HAState oldState, HAState newState);

    /**
     * Sequence State changes can include: Connected, Bound, Recovering, RecoveringFromFlow, UpToDate
     *
     * @param oldState -- previous Sequence State
     * @param newState -- new Sequence State
     */
    void OnSeqStateChange(SeqState oldState, SeqState newState);

    /**
     * Called when the last output message from the cluster LVQ was read for recovery purposes
     *
     * @param initialState -- last output value from the cluster LVQ
     */
    void OnInitialStateMessage(OutputType initialState);

    /**
     * Called when a new input message arrives from the application queue. Note, this is
     * invoked for informational purposes, regardless of the Sequence State of the instance.
     * It should not be interpreted as mandating application state change, as the cluster
     * may still be recovering from the input flow. When the Sequence State is up-to-date,
     * a separate method will be called giving the application an opportunity to update
     * internal application state and send a matching output message.
     *
     * See UpdateApplicationState below.
     *
     * @param input -- last input message from the application queue
     */
    void OnApplicationMessage(InputType input);

    /**
     * This is an important variation of OnApplicationMessage called by the ClusterConnector
     * when it calculates that the cluster instance is up-to-date with the input stream,
     * so every input requires an updated state output.
     *
     * @param input -- the input message driving a potential application state change
     * @return new output state reflecting the input
     */
    OutputType UpdateApplicationState(InputType input);
}
