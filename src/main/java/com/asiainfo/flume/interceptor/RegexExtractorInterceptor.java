package com.asiainfo.flume.interceptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.interceptor.Interceptor;
import org.apache.flume.interceptor.RegexExtractorInterceptorPassThroughSerializer;
import org.apache.flume.interceptor.RegexExtractorInterceptorSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;

/**
 * flume interceptor 数据清洗、格式转换
 * 
 * @author       zq
 * @date         2018年1月11日  上午11:06:06
 * Copyright: 	  北京亚信智慧数据科技有限公司
 */
public class RegexExtractorInterceptor implements Interceptor {
    
    private static final Logger logger = LoggerFactory.getLogger(RegexExtractorInterceptor.class);
    static final String REGEX = "regex";
    static final String SERIALIZERS = "serializers";
    private final Pattern regex;
    private final List<RegexExtractorInterceptor.NameAndSerializer> serializers;
    private static Map<String, String> values = new HashMap<String, String>();

    private RegexExtractorInterceptor(Pattern regex, List<RegexExtractorInterceptor.NameAndSerializer> serializers) {
        this.regex = regex;
        this.serializers = serializers;
    }

    @Override public void initialize() {}

    @Override public void close() {}

    @Override
    public Event intercept(Event event) {
        
        Matcher matcher = this.regex.matcher(new String(event.getBody(), Charsets.UTF_8));
        Map<String, String> headers = event.getHeaders();
        if (matcher.find()) {
            int group = 0;
            for (int count = matcher.groupCount(); group < count; ++group) {
                int groupIndex = group + 1;
                if (groupIndex > this.serializers.size()) {
                    logger.debug("Skipping group {} to {} due to missing serializer", Integer.valueOf(group), Integer.valueOf(count));
                    break;
                }
                NameAndSerializer serializer = (RegexExtractorInterceptor.NameAndSerializer) this.serializers.get(group);
                logger.debug("Serializing {} using {}", serializer.headerName, serializer.serializer);
                headers.put(serializer.headerName, serializer.serializer.serialize(matcher.group(groupIndex)));
                values.put(serializer.headerName, serializer.serializer.serialize(matcher.group(groupIndex)));
            }
        } else {
            for (Map.Entry<String, String> en : values.entrySet()) {
                headers.put(en.getKey(), en.getValue());
            }
        }
        return event;
    }

    @Override
    public List<Event> intercept(List<Event> events) {
        
        List<Event> result = Lists.newArrayListWithCapacity(events.size());
        for (Event event : events) {
            Event interceptedEvent = this.intercept(event);
            if (interceptedEvent != null) {
                result.add(interceptedEvent);
            }
        }
        return result;
    }

    static class NameAndSerializer {
        
        private final String headerName;
        private final RegexExtractorInterceptorSerializer serializer;
        public NameAndSerializer(String headerName, RegexExtractorInterceptorSerializer serializer) {
            this.headerName = headerName;
            this.serializer = serializer;
        }
    }

    public static class Builder implements org.apache.flume.interceptor.Interceptor.Builder {
        
        private Pattern regex;
        private List<RegexExtractorInterceptor.NameAndSerializer> serializerList;
        private final RegexExtractorInterceptorSerializer defaultSerializer = new RegexExtractorInterceptorPassThroughSerializer();

        @Override
        public void configure(Context context) {
            
            String regexString = context.getString("regex");
            Preconditions.checkArgument(!StringUtils.isEmpty(regexString), "Must supply a valid regex string");
            this.regex = Pattern.compile(regexString);
            this.configureSerializers(context);
        }

        private void configureSerializers(Context context) {
            
            String serializerListStr = context.getString("serializers");
            Preconditions.checkArgument(!StringUtils.isEmpty(serializerListStr), "Must supply at least one name and serializer");
            String[] serializerNames = serializerListStr.split("\\s+");
            Context serializerContexts = new Context(context.getSubProperties("serializers."));
            this.serializerList = Lists.newArrayListWithCapacity(serializerNames.length);
            String[] arr = serializerNames;
            int len = serializerNames.length;

            for (int i = 0; i < len; ++i) {
                String serializerName = arr[i];
                Context serializerContext = new Context(serializerContexts.getSubProperties(serializerName + "."));
                String type = serializerContext.getString("type", "DEFAULT");
                String name = serializerContext.getString("name");
                Preconditions.checkArgument(!StringUtils.isEmpty(name), "Supplied name cannot be empty.");
                if ("DEFAULT".equals(type)) {
                    this.serializerList.add(new RegexExtractorInterceptor.NameAndSerializer(name, this.defaultSerializer));
                } else {
                    this.serializerList.add(new RegexExtractorInterceptor.NameAndSerializer(name, this.getCustomSerializer(type, serializerContext)));
                }
            }

        }

        private RegexExtractorInterceptorSerializer getCustomSerializer(String clazzName, Context context) {
            try {
                RegexExtractorInterceptorSerializer serializer = (RegexExtractorInterceptorSerializer) Class.forName(clazzName).newInstance();
                serializer.configure(context);
                return serializer;
            } catch (Exception ex) {
                RegexExtractorInterceptor.logger.error("Could not instantiate event serializer.", ex);
                Throwables.propagate(ex);
                return this.defaultSerializer;
            }
        }

        @Override
        public Interceptor build() {
            Preconditions.checkArgument(this.regex != null, "Regex pattern was misconfigured");
            Preconditions.checkArgument(this.serializerList.size() > 0, "Must supply a valid group match id list");
            return new RegexExtractorInterceptor(this.regex, this.serializerList);
        }
    }
}
