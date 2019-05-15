package com.asiainfo.flume.sink;

import org.apache.flume.Context;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;

/**   
 * @Description: TODO
 * 
 * @author chenzq  
 * @date 2019年4月24日 下午5:32:46
 * @version V1.0
 * @Copyright: Copyright(c) 2019 jaesonchen.com Inc. All rights reserved. 
 */
public class RedisSink extends AbstractSink implements Configurable {

    /*
     * @see org.apache.flume.Sink#process()
     */
    @Override
    public Status process() throws EventDeliveryException {
        // TODO Auto-generated method stub
        return null;
    }

    /*
     * @see org.apache.flume.conf.Configurable#configure(org.apache.flume.Context)
     */
    @Override
    public void configure(Context context) {
        // TODO Auto-generated method stub

    }
}
