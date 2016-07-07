package com.solacesystems.poc.model;

import com.solacesystems.solclientj.core.handle.MessageHandle;

import java.nio.ByteBuffer;

/**
 * Used by the ClusterConnector to serialize/deserialize application messages. Decoupling this from the
 * ClusterConnector and ClusterModel allows different application instances to define their own messages
 * for application input and application state output.
 *
 * @param <InputType> -- input message type; must extend Ordered to ensure a sequence number is present
 * @param <OutputType>-- output message type; must also extend Ordered to ensure a sequence number is present
 */
public interface ClusteredAppSerializer<InputType extends Ordered, OutputType extends Ordered> {

    InputType DeserializeInput(MessageHandle msg);

    OutputType DeserializeOutput(MessageHandle msg);

    ByteBuffer SerializeOutput(OutputType output);
}
