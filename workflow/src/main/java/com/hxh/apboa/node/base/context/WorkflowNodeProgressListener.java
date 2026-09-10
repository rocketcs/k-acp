package com.hxh.apboa.node.base.context;

import com.hxh.apboa.node.base.NodeOutput;

/** Receives real-time lifecycle changes for nodes in a workflow run. */
public interface WorkflowNodeProgressListener {
    void onNodeStarted(NodeOutput output);

    void onNodeFinished(NodeOutput output);
}
