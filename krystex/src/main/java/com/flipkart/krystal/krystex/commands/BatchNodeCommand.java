package com.flipkart.krystal.krystex.commands;

import com.flipkart.krystal.krystex.request.RequestId;
import java.util.Set;

public sealed interface BatchNodeCommand extends NodeCommand
    permits ForwardBatchCommand, CallbackBatchCommand {

  Set<RequestId> requestIds();

  Set<String> inputNames();
}
