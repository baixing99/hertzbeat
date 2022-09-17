/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.usthe.manager.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usthe.common.annotation.ParamType;
import com.usthe.common.annotation.ProtocolType;
import com.usthe.common.entity.job.Job;
import com.usthe.common.entity.job.Metrics;
import com.usthe.common.entity.request.BasePageRequest;
import com.usthe.manager.custom.ParamTypeDispatch;
import com.usthe.manager.custom.ProtocolTypeDiptach;
import com.usthe.manager.dao.ParamDefineDao;
import com.usthe.manager.pojo.dto.Hierarchy;
import com.usthe.manager.pojo.dto.ParamDefineDto;
import com.usthe.common.entity.manager.ParamDefine;
import com.usthe.manager.pojo.vo.CustomMonitorDefinedVo;
import com.usthe.manager.pojo.vo.CustomMonitorParamVo;
import com.usthe.manager.pojo.vo.CustomMonitorVo;
import com.usthe.manager.service.AppService;
import com.usthe.manager.support.exception.MonitorMetricsException;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONObject;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Monitoring Type Management Implementation
 * 监控类型管理实现
 * TODO temporarily stores the monitoring configuration and parameter configuration in memory and then stores it in the
 * 暂时将监控配置和参数配置存放内存 之后存入数据库
 *
 * @author tomsun28
 * @date 2021/11/14 17:17
 */
@Service
@Order(value = 1)
@Slf4j
public class AppServiceImpl implements AppService, CommandLineRunner {

    private final Map<String,CustomMonitorVo> appCustomDefines = new ConcurrentHashMap<>();
    private final Map<String, Class<ParamTypeDispatch>> paramClassMap = new ConcurrentHashMap<>();
    private final Map<String, Class<ProtocolTypeDiptach>> protocolClassMap = new ConcurrentHashMap<>();
    private final Map<String, Job> appDefines = new ConcurrentHashMap<>();
    private final Map<String, List<ParamDefine>> paramDefines = new ConcurrentHashMap<>();

    @Autowired
    private ParamDefineDao paramDefineDao;

    @Autowired
    private ApplicationContext applicationContext;

    @Override
    public List<ParamDefine> getAppParamDefines(String app) {
        List<ParamDefine> params = paramDefines.get(app);
        if (params == null) {
            params = Collections.emptyList();
        }
        return params;
    }

    @Override
    public Job getAppDefine(String app) throws IllegalArgumentException {
        Job appDefine = appDefines.get(app);
        if (appDefine == null) {
            throw new IllegalArgumentException("The app " + app + " not support.");
        }
        return appDefine.clone();
    }

    @Override
    public List<String> getAppDefineMetricNames(String app) {
        List<String> metricNames = new ArrayList<>(16);
        if (StringUtils.hasLength(app)) {
            Job appDefine = appDefines.get(app);
            if (appDefine == null) {
                throw new IllegalArgumentException("The app " + app + " not support.");
            }
            metricNames.addAll(appDefine.getMetrics().stream().map(Metrics::getName).collect(Collectors.toList()));
        } else {
            appDefines.forEach((k,v)-> metricNames.addAll(v.getMetrics().stream().map(Metrics::getName).collect(Collectors.toList())));
        }
        return metricNames;
    }


    @Override
    public Map<String, String> getI18nResources(String lang) {
        Map<String, String> i18nMap = new HashMap<>(128);
        for (Job job : appDefines.values()) {
            // todo needs to support the indicator name
            // 后面需要支持指标名称
            Map<String, String> name = job.getName();
            if (name != null && !name.isEmpty()) {
                String i18nName = name.get(lang);
                if (i18nName == null) {
                    i18nName = name.values().stream().findFirst().get();
                }
                i18nMap.put("monitor.app." + job.getApp(), i18nName);
            }
        }
        for (Map.Entry<String, List<ParamDefine>> entry : paramDefines.entrySet()) {
            String app = entry.getKey();
            for (ParamDefine paramDefine : entry.getValue()) {
                Map<String, String> name = paramDefine.getName();
                if (name != null && !name.isEmpty()) {
                    String i18nName = name.get(lang);
                    if (i18nName == null) {
                        i18nName = name.values().stream().findFirst().get();
                    }
                    i18nMap.put("monitor.app." + app + ".param." + paramDefine.getField(), i18nName);
                }
            }
        }
        return i18nMap;
    }

    @Override
    public List<Hierarchy> getAllAppHierarchy(String lang) {
        List<Hierarchy> hierarchies = new LinkedList<>();
        for (Job job : appDefines.values()) {
            Hierarchy hierarchyApp = new Hierarchy();
            hierarchyApp.setCategory(job.getCategory());
            hierarchyApp.setValue(job.getApp());
            Map<String, String> nameMap = job.getName();
            if (nameMap != null) {
                String i18nName = nameMap.get(lang);
                if (i18nName == null) {
                    i18nName = nameMap.values().stream().findFirst().get();
                }
                hierarchyApp.setLabel(i18nName);
            }
            List<Hierarchy> hierarchyMetricList = new LinkedList<>();
            if (job.getMetrics() != null) {
                for (Metrics metrics : job.getMetrics()) {
                    Hierarchy hierarchyMetric = new Hierarchy();
                    hierarchyMetric.setValue(metrics.getName());
                    hierarchyMetric.setLabel(metrics.getName());
                    List<Hierarchy> hierarchyFieldList = new LinkedList<>();
                    if (metrics.getFields() != null) {
                        for (Metrics.Field field : metrics.getFields()) {
                            Hierarchy hierarchyField = new Hierarchy();
                            hierarchyField.setValue(field.getField());
                            hierarchyField.setLabel(field.getField());
                            hierarchyField.setIsLeaf(true);
                            hierarchyFieldList.add(hierarchyField);
                        }
                        hierarchyMetric.setChildren(hierarchyFieldList);
                    }
                    hierarchyMetricList.add(hierarchyMetric);
                }
            }
            hierarchyApp.setChildren(hierarchyMetricList);
            hierarchies.add(hierarchyApp);
        }
        return hierarchies;
    }

    @Override
    public void setCustomInfo(CustomMonitorVo customMonitorVo) {
        if(Objects.isNull(customMonitorVo.getApp()) || Objects.isNull(customMonitorVo.getName()) || Objects.isNull(customMonitorVo.getCategory())){
            throw new MonitorMetricsException("The custom monitor `app`,`name`,`category` must not null");
        }
        //verify duplicate
        if (Objects.nonNull(appCustomDefines.get(customMonitorVo.getApp().toLowerCase()))) {
            throw new MonitorMetricsException("finding the same app ,checking please!");
        }
        appCustomDefines.put(customMonitorVo.getApp().toLowerCase(),customMonitorVo);
    }

    @Override
    public void setCustomParamInfo(CustomMonitorParamVo customMonitorParamVo) {
        CustomMonitorVo customMonitorVo = appCustomDefines.get(customMonitorParamVo.getApp().toLowerCase());
        customMonitorVo.setCustomMonitorParamVo(customMonitorParamVo);
        appCustomDefines.put(customMonitorVo.getApp().toLowerCase(),customMonitorVo);
    }

    @Override
    public void setCustomDefinedInfo(CustomMonitorDefinedVo customMonitorDefinedVo){
        String classpath = this.getClass().getClassLoader().getResource("").getPath();
        CustomMonitorVo customMonitorVo = appCustomDefines.get(customMonitorDefinedVo.getApp().toLowerCase());
        customMonitorVo.setCustomMonitorDefinedVo(customMonitorDefinedVo);
        appCustomDefines.put(customMonitorVo.getApp().toLowerCase(),customMonitorVo);
        //save file
        Yaml yaml = new Yaml();
        //分别对参数信息以及定义信息进行存储,首先存储参数信息
        CustomMonitorParamVo customMonitorParamVo = customMonitorVo.getCustomMonitorParamVo();
        //获取参数类型注解
        try {
            Map<String, Object> paramMap = new HashMap<>(16);
            paramMap.put("app", customMonitorParamVo.getApp());
            paramMap.put("param", customMonitorParamVo.getParams().stream().map(item -> {
                ParamTypeDispatch contextBean = applicationContext.getBean(paramClassMap.get(item.getType()));
                Map<String, Object> dispatch = contextBean.dispatch(item);
                dispatch.put("field",item.getField());
                dispatch.put("name",item.getName());
                dispatch.put("required",item.getRequired());
                dispatch.put("defaultValue",item.getDefaultValue());
                return dispatch;
            }).collect(Collectors.toList()));
            String paramPath = classpath + File.separator + "define" + File.separator + "param";
            FileWriter paramWriter = new FileWriter(paramPath + "param-" + customMonitorDefinedVo.getApp() + ".yml");
            //定义信息获取
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> definedMap = objectMapper.readValue(objectMapper.writeValueAsString(customMonitorDefinedVo), Map.class);
            if (Objects.nonNull(definedMap.get("metrics"))) {
                definedMap.put("metrics", customMonitorDefinedVo.getMetrics().stream().map(item -> {
                    ProtocolTypeDiptach contextBean = applicationContext.getBean(protocolClassMap.get(item.getProtocol()));
                    return contextBean.dispatch(item);
                }).collect(Collectors.toList()));
            }
            String definedPath = classpath + File.separator + "define" + File.separator + "app";
            FileWriter definedWriter = new FileWriter(definedPath + "app-" + customMonitorDefinedVo.getApp() + ".yml");
            yaml.dump(paramMap, paramWriter);
            yaml.dump(definedMap, definedWriter);
        }catch (Exception e){
            log.error(e.getMessage(), e);
            throw new MonitorMetricsException("save fail");
        }
    }

    @Override
    public List<CustomMonitorVo> getAllCustomInfo(BasePageRequest basePageRequest) {
        List<CustomMonitorVo> result = new LinkedList<>();
        //先判断文件是否为空，如果为空则直接从文件夹取
        if(!CollectionUtils.isEmpty(appCustomDefines)){
            appCustomDefines.forEach((k,v)-> result.add(v));
        }

        return result;
    }

    @Override
    public CustomMonitorVo getOneCustomInfo(String app) {
        return null;
    }

    @Override
    public void updateCustomInfo(CustomMonitorVo customMonitorVo) {
        //先更新数据库

    }

    @Override
    public void run(String... args) throws Exception {
        boolean loadFromFile = true;
        final List<InputStream> inputStreams = new LinkedList<>();
        // 读取app定义配置加载到内存中 define/app/*.yml
        Yaml yaml = new Yaml();
        String classpath = this.getClass().getClassLoader().getResource("").getPath();
        String defineAppPath = classpath + File.separator + "define" + File.separator + "app";
        File directory = new File(defineAppPath);
        if (!directory.exists() || directory.listFiles() == null) {
            classpath = this.getClass().getResource(File.separator).getPath();
            defineAppPath = classpath + File.separator + "define" + File.separator + "app";
            directory = new File(defineAppPath);
            if (!directory.exists() || directory.listFiles() == null) {
                // load define app yml in jar
                log.info("load define app yml in internal jar");
                loadFromFile = false;
                try {
                    ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                    Resource[] resources = resolver.getResources("classpath:define/app/*.yml");
                    for (Resource resource : resources) {
                        inputStreams.add(resource.getInputStream());
                    }
                } catch (Exception e) {
                    log.error("define app yml not exist");
                    throw e;
                }
            }
        }
        if (loadFromFile) {
            log.info("load define path {}", defineAppPath);
            for (File appFile : Objects.requireNonNull(directory.listFiles())) {
                if (appFile.exists()) {
                    try (FileInputStream fileInputStream = new FileInputStream(appFile)) {
                        Job app = yaml.loadAs(fileInputStream, Job.class);
                        appDefines.put(app.getApp().toLowerCase(), app);
                    } catch (IOException e) {
                        log.error(e.getMessage(), e);
                        throw new IOException(e);
                    }
                }
            }
        } else {
            if (inputStreams.isEmpty()) {
                throw new IllegalArgumentException("define app directory not exist");
            } else {
                inputStreams.forEach(stream -> {
                    try {
                        Job app = yaml.loadAs(stream, Job.class);
                        appDefines.put(app.getApp().toLowerCase(), app);
                        stream.close();
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                });
            }
        }

        // 读取监控参数定义配置加载到数据库中 define/param/*.yml
        if (loadFromFile) {
            String defineParamPath = classpath + File.separator + "define" + File.separator + "param";
            directory = new File(defineParamPath);
            if (!directory.exists() || directory.listFiles() == null) {
                throw new IllegalArgumentException("define param directory not exist: " + defineParamPath);
            }
            for (File appFile : Objects.requireNonNull(directory.listFiles())) {
                if (appFile.exists()) {
                    try (FileInputStream fileInputStream = new FileInputStream(appFile)) {
                        ParamDefineDto paramDefine = yaml.loadAs(fileInputStream, ParamDefineDto.class);
                        paramDefines.put(paramDefine.getApp().toLowerCase(), paramDefine.getParam());
                    } catch (IOException e) {
                        log.error(e.getMessage(), e);
                        throw new IOException(e);
                    }
                }
            }
        } else {
            try {
                ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                Resource[] resources = resolver.getResources("classpath:define/param/*.yml");
                for (Resource resource : resources) {
                    InputStream stream = resource.getInputStream();
                    ParamDefineDto paramDefine = yaml.loadAs(stream, ParamDefineDto.class);
                    paramDefines.put(paramDefine.getApp().toLowerCase(), paramDefine.getParam());
                    stream.close();
                }
            } catch (Exception e) {
                log.error("define param yml not exist");
                throw e;
            }
        }
        //加载param和protocol类
        applicationContext.getBeansWithAnnotation(ParamType.class)
                .entrySet().iterator()
                .forEachRemaining(entry->{
                    Class<ParamTypeDispatch> paramTypeDispatchClass = (Class<ParamTypeDispatch>) entry.getValue().getClass();
                    paramClassMap.put(paramTypeDispatchClass.getAnnotation(ParamType.class).name(),paramTypeDispatchClass);
                });
        applicationContext.getBeansWithAnnotation(ProtocolType.class)
                .entrySet().iterator()
                .forEachRemaining(entry->{
                    Class<ProtocolTypeDiptach> classInfo = (Class<ProtocolTypeDiptach>) entry.getValue().getClass();
                    protocolClassMap.put(classInfo.getAnnotation(ProtocolType.class).name(),classInfo);
                });
    }
}
