/*
 * Copyright (C) 2018 LEIDOS.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package gov.dot.fhwa.saxton.carma.plugins.platooning;

import java.util.List;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import cav_msgs.MobilityOperation;
import cav_msgs.MobilityRequest;
import cav_msgs.MobilityResponse;
import cav_msgs.PlatooningInfo;
import cav_msgs.RoadwayEnvironment;
import cav_msgs.SpeedAccel;
import gov.dot.fhwa.saxton.carma.guidance.arbitrator.TrajectoryPlanningResponse;
import gov.dot.fhwa.saxton.carma.guidance.conflictdetector.ConflictSpace;
import gov.dot.fhwa.saxton.carma.guidance.lightbar.ILightBarManager;
import gov.dot.fhwa.saxton.carma.guidance.lightbar.IndicatorStatus;
import gov.dot.fhwa.saxton.carma.guidance.lightbar.LightBarIndicator;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.IManeuverInputs;
import gov.dot.fhwa.saxton.carma.guidance.mobilityrouter.MobilityOperationHandler;
import gov.dot.fhwa.saxton.carma.guidance.mobilityrouter.MobilityRequestHandler;
import gov.dot.fhwa.saxton.carma.guidance.mobilityrouter.MobilityRequestResponse;
import gov.dot.fhwa.saxton.carma.guidance.mobilityrouter.MobilityResponseHandler;
import gov.dot.fhwa.saxton.carma.guidance.plugins.AbstractPlugin;
import gov.dot.fhwa.saxton.carma.guidance.plugins.IStrategicPlugin;
import gov.dot.fhwa.saxton.carma.guidance.plugins.PluginServiceLocator;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.IPublisher;
import gov.dot.fhwa.saxton.carma.guidance.pubsub.ISubscriber;
import gov.dot.fhwa.saxton.carma.guidance.trajectory.Trajectory;

public class PlatooningPlugin extends AbstractPlugin
        implements IStrategicPlugin, MobilityOperationHandler, MobilityRequestHandler, MobilityResponseHandler {
    
    // TODO the plugin should use interface manager once rosjava multiple thread service call is fixed
    protected static final String SPEED_CMD_CAPABILITY    = "/saxton_cav/drivers/srx_controller/control/cmd_speed";
    protected static final String PLATOONING_FLAG         = "PLATOONING";
    protected static final String MOBILITY_STRATEGY       = "Carma/Platooning";
    protected static final String JOIN_AT_REAR_PARAMS     = "SIZE:%d,SPEED:%.2f,DTD:%.2f";
    protected static final String OPERATION_INFO_PARAMS   = "INFO|REAR:%s,LENGTH:%.2f,SPEED:%.2f,SIZE:%d,DTD:%.2f";
    protected static final String OPERATION_STATUS_PARAMS = "STATUS|CMDSPEED:%.2f,DTD:%.2f,SPEED:%.2f";
    protected static final String OPERATION_INFO_TYPE     = "INFO";
    protected static final String OPERATION_STATUS_TYPE   = "STATUS";
    protected static final int    STATUS_INTERVAL_LENGTH  = 100;   // ms
    protected static final int    INFO_INTERVAL_LENGTH    = 3000;  // ms
    protected static final int    NEGOTIATION_TIMEOUT     = 5000;  // ms

    // initialize pubs/subs
    protected IPublisher<MobilityRequest>     mobilityRequestPublisher;
    protected IPublisher<MobilityOperation>   mobilityOperationPublisher;
    protected IPublisher<PlatooningInfo>      platooningInfoPublisher;
    protected ISubscriber<SpeedAccel>         cmdSpeedSub;
    protected ISubscriber<RoadwayEnvironment> roadwaySub;

    // following parameters are for general platooning plugin
    protected double maxAccel              = 2.5;  // m/s/s
    protected double minimumManeuverLength = 15.0; // m
    protected double kpPID                 = 1.5;  // 1
    protected double kiPID                 = 0.0;  // 1
    protected double kdPID                 = -0.1; // 1
    protected double statusTimeoutFactor   = 2.5;  // 1
    protected double vehicleLength         = 5.0;  // m
    protected int    maxPlatoonSize        = 10;   // 1
    protected int    algorithmType         = 0;    // N/A
    
    // following parameters are for platoon forming and operation
    protected double timeHeadway           = 2.0;  // s
    protected double standStillHeadway     = 12.0; // m
    protected double maxAllowedJoinTimeGap = 15.0; // s
    protected double maxAllowedJoinGap     = 90.0; // m
    protected double desiredJoinTimeGap    = 4.0;  // s
    protected double desiredJoinGap        = 30.0; // m
    protected double waitingStateTimeout   = 25.0; // s
    protected double cmdSpeedMaxAdjustment = 10.0; // m/s
    
    // following parameters are mainly for APF leader selection
    protected double lowerBoundary         = 1.6;  // s
    protected double upperBoundary         = 1.7;  // s
    protected double maxSpacing            = 4.0;  // s
    protected double minSpacing            = 3.9;  // s
    protected double minGap                = 22.0; // m
    protected double maxGap                = 32.0; // m

    // following parameters are flags for different caps on platooning controller output
    protected boolean speedLimitCapEnabled  = true;
    protected boolean maxAccelCapEnabled    = true;
    protected boolean leaderSpeedCapEnabled = true;
    
    // platooning plug-in components
    protected IPlatooningState state                  = null;
    protected Thread           stateThread            = null;
    protected CommandGenerator commandGenerator       = null;
    protected Thread           commandGeneratorThread = null;
    protected PlatoonManager   platoonManager         = null;
    protected Thread           platoonManagerThread   = null;
    
    // initialize a lock for handle mobility messages
    protected Object sharedLock = new Object();

    // Light Bar Control
    protected final LightBarIndicator LIGHT_BAR_INDICATOR = LightBarIndicator.YELLOW;
    protected ILightBarManager lightBarManager;
    protected AtomicBoolean lostControlOfLights = new AtomicBoolean(false);
    protected final int LOOPS_PER_REQUEST = 10;
    protected int requestControlLoopsCount = 0;
    protected IndicatorStatus lastAttemptedIndicatorStatus = IndicatorStatus.OFF;
    
    // Control on MobilityRouter
    protected AtomicBoolean handleMobilityPath = new AtomicBoolean(true);

    public PlatooningPlugin(PluginServiceLocator pluginServiceLocator) {
        super(pluginServiceLocator);
        version.setName("Platooning Plugin");
        version.setMajorRevision(1);
        version.setIntermediateRevision(1);
        version.setMinorRevision(0);
    }

    @Override
    public void onInitialize() {
        log.info("Platooning plugin is initializing...");
        // initialize parameters of platooning plugin
        maxAccel                = pluginServiceLocator.getParameterSource().getDouble("~platooning_max_accel", 2.5);
        minimumManeuverLength   = pluginServiceLocator.getParameterSource().getDouble("~platooning_min_maneuver_length", 15.0);
        kpPID                   = pluginServiceLocator.getParameterSource().getDouble("~platooning_Kp", 1.5);
        kiPID                   = pluginServiceLocator.getParameterSource().getDouble("~platooning_Ki", 0.0);
        kdPID                   = pluginServiceLocator.getParameterSource().getDouble("~platooning_Kd", -0.1);
        statusTimeoutFactor     = pluginServiceLocator.getParameterSource().getDouble("~platooning_status_timeout_factor", 2.5);
        vehicleLength           = pluginServiceLocator.getParameterSource().getDouble("vehicle_width", 5.0);
        maxPlatoonSize          = pluginServiceLocator.getParameterSource().getInteger("~platooning_max_size", 10);
        algorithmType           = pluginServiceLocator.getParameterSource().getInteger("~platooning_algorithm_type", 0);
        timeHeadway             = pluginServiceLocator.getParameterSource().getDouble("~platooning_desired_time_headway", 2.0);
        standStillHeadway       = pluginServiceLocator.getParameterSource().getDouble("~platooning_stand_still_headway", 12.0);
        maxAllowedJoinTimeGap   = pluginServiceLocator.getParameterSource().getDouble("~platooning_max_join_timegap", 15.0);
        maxAllowedJoinGap       = pluginServiceLocator.getParameterSource().getDouble("~platooning_max_join_gap", 90.0);
        desiredJoinTimeGap      = pluginServiceLocator.getParameterSource().getDouble("~platooning_desired_join_timegap", 4.0);
        desiredJoinGap          = pluginServiceLocator.getParameterSource().getDouble("~platooning_desired_join_gap", 30.0);
        waitingStateTimeout     = pluginServiceLocator.getParameterSource().getDouble("~platooning_waiting_state_timeout", 25.0);
        cmdSpeedMaxAdjustment   = pluginServiceLocator.getParameterSource().getDouble("~platooning_cmd_max_adjustment", 10.0);
        lowerBoundary           = pluginServiceLocator.getParameterSource().getDouble("~platooning_lower_boundary", 1.6);
        upperBoundary           = pluginServiceLocator.getParameterSource().getDouble("~platooning_upper_boundary", 1.7);
        maxSpacing              = pluginServiceLocator.getParameterSource().getDouble("~platooning_max_spacing", 4.0);
        minSpacing              = pluginServiceLocator.getParameterSource().getDouble("~platooning_min_spacing", 3.9);
        minGap                  = pluginServiceLocator.getParameterSource().getDouble("~platooning_min_gap", 22.0);
        maxGap                  = pluginServiceLocator.getParameterSource().getDouble("~platooning_max_gap", 32.0);
        speedLimitCapEnabled    = pluginServiceLocator.getParameterSource().getBoolean("~platooning_local_speed_limit_cap", true);
        maxAccelCapEnabled      = pluginServiceLocator.getParameterSource().getBoolean("~platooning_max_accel_cap", true);
        leaderSpeedCapEnabled   = pluginServiceLocator.getParameterSource().getBoolean("~platooning_max_cmd_speed_adjustment_cap", true);
        
        //log all loaded parameters
        log.debug("Load param maxAccel = " + maxAccel);
        log.debug("Load param minimumManeuverLength = " + minimumManeuverLength);
        log.debug("Load param for speed PID controller: [p = " + kpPID + ", i = " + kiPID + ", d = " + kdPID + "]");
        log.debug("Load param messageTimeoutFactor = " + statusTimeoutFactor);
        log.debug("Load param vehicleLength = " + vehicleLength);
        log.debug("Load param maxPlatoonSize = " + maxPlatoonSize);
        log.debug("Load param algorithmType = " + algorithmType);
        log.debug("Load param timeHeadway = " + timeHeadway);
        log.debug("Load param standStillHeadway = " + standStillHeadway);
        log.debug("Load param maxAllowedJoinTimeGap = " + maxAllowedJoinTimeGap);
        log.debug("Load param maxAllowedJoinGap = " + maxAllowedJoinGap);
        log.debug("Load param desiredJoinTimeGap = " + desiredJoinTimeGap);
        log.debug("Load param desiredJoinGap = " + desiredJoinGap);
        log.debug("Load param waitingStateTimeout = " + waitingStateTimeout);
        log.debug("Load param cmdSpeedMaxAdjustment = " + cmdSpeedMaxAdjustment);
        log.debug("Load param lowerBoundary = " + lowerBoundary);        
        log.debug("Load param upperBoundary = " + upperBoundary);        
        log.debug("Load param maxSpacing = " + maxSpacing);
        log.debug("Load param minSpacing = " + minSpacing);  
        log.debug("Load param minGap = " + minGap);
        log.debug("Load param maxGap = " + maxGap);
        log.debug("Load param speedLimitCapEnabled = " + speedLimitCapEnabled);
        log.debug("Load param maxAccelCapEnabled = " + maxAccelCapEnabled);
        log.debug("Load param leaderSpeedCapEnabled = " + leaderSpeedCapEnabled);
        
        // initialize necessary pubs/subs
        mobilityRequestPublisher   = pubSubService.getPublisherForTopic("outgoing_mobility_request", MobilityRequest._TYPE);
        mobilityOperationPublisher = pubSubService.getPublisherForTopic("outgoing_mobility_operation", MobilityOperation._TYPE);
        platooningInfoPublisher    = pubSubService.getPublisherForTopic("platooning_info", PlatooningInfo._TYPE);
        cmdSpeedSub                = pubSubService.getSubscriberForTopic(SPEED_CMD_CAPABILITY, SpeedAccel._TYPE);
        roadwaySub                 = pubSubService.getSubscriberForTopic("roadway_environment", RoadwayEnvironment._TYPE);
        
        // get light bar manager
        lightBarManager = pluginServiceLocator.getLightBarManager();
        
        // get control on mobility path capability
        AtomicBoolean tempCapability = pluginServiceLocator.getMobilityRouter().acquireDisableMobilityPathCapability();
        if(tempCapability != null) {
            this.handleMobilityPath = tempCapability;
            log.debug("Acquired control on mobility router for handling mobility path");
        } else {
            log.warn("Try to acquire control on mobility router for handling mobility path but failed");
        }
        
        log.info("Platooning plugin is initialized.");
    }

    @Override
    public void onResume() {
        // register with MobilityRouter
        pluginServiceLocator.getMobilityRouter().registerMobilityRequestHandler(MOBILITY_STRATEGY, this);
        pluginServiceLocator.getMobilityRouter().registerMobilityResponseHandler(this);
        pluginServiceLocator.getMobilityRouter().registerMobilityOperationHandler(MOBILITY_STRATEGY, this);
        // reset plug-in's sub-components
        this.setState(new StandbyState(this, log, pluginServiceLocator));
        if(platoonManagerThread == null) {
            platoonManager       = new PlatoonManager(this, log, pluginServiceLocator);
            platoonManagerThread = new Thread(platoonManager);
            platoonManagerThread.setName("Platooning List Manager");
            platoonManagerThread.start();
            log.debug("Started platoonManagerThread");
        }
        if(commandGeneratorThread == null) {
            commandGenerator       = new CommandGenerator(this, log, pluginServiceLocator);
            commandGeneratorThread = new Thread(commandGenerator);
            commandGeneratorThread.setName("Platooning Command Generator");
            commandGeneratorThread.start();
            log.debug("Started commandGeneratorThread");
        }
        // Take control of light bar indicator
        takeControlOfLightBar();
        log.info("Platooning plugin resume to operate.");
        log.info("The current platooning plugin state is " + this.state.toString());
        this.setAvailability(true);
    }

    @Override
    public void onSuspend() {
        this.setAvailability(false);
        if(stateThread != null) {
            stateThread.interrupt();
            stateThread = null;
        }
        if(commandGeneratorThread != null) {
            commandGeneratorThread.interrupt();
            commandGeneratorThread = null;
        }
        if(platoonManagerThread != null) {
            platoonManagerThread.interrupt();
            platoonManagerThread = null;
        }
        // Turn off lights and release control
        lightBarManager.setIndicator(LIGHT_BAR_INDICATOR, IndicatorStatus.OFF, this.getVersionInfo().componentName());
        lightBarManager.releaseControl(Arrays.asList(LIGHT_BAR_INDICATOR), this.getVersionInfo().componentName());
        // Release control on mobility router
        pluginServiceLocator.getMobilityRouter().releaseDisableMobilityPathCapability(handleMobilityPath);
        log.info("Platooning plugin is suspended.");
    }
    
    @Override
    public void onTerminate() {
        // NO-OP
    }
    
    @Override
    public void loop() throws InterruptedException {
        // publish platooning information message for the usage of UI
        publishPlatooningInfo();
        // Request control of light bar if needed
        if (lostControlOfLights.get() == true) {
            if (requestControlLoopsCount == LOOPS_PER_REQUEST) {
                takeControlOfLightBar();
                requestControlLoopsCount = 0;
            }
        }
        Thread.sleep(STATUS_INTERVAL_LENGTH);
    }

    @Override
    public TrajectoryPlanningResponse planTrajectory(Trajectory traj, double expectedEntrySpeed) {
        log.info("Plan Trajectory from " + traj.toString() + " in state " + state.toString());
        return this.state.planTrajectory(traj, expectedEntrySpeed);
    }
    
    @Override
    public void handleMobilityOperationMessage(MobilityOperation msg) {
        synchronized (this.sharedLock) {
            this.state.onMobilityOperationMessage(msg);
        }
    }
    
    @Override
    public MobilityRequestResponse handleMobilityRequestMessage(MobilityRequest msg, boolean hasConflict, ConflictSpace conflictSpace) {
        synchronized (this.sharedLock) {
            return this.state.onMobilityRequestMessgae(msg);
        }
    }
    
    @Override
    public void handleMobilityResponseMessage(MobilityResponse msg) {
        synchronized (this.sharedLock) {
            this.state.onMobilityResponseMessage(msg);
        }
    }
    
    // Set state for the current plug-in
    protected void setState(IPlatooningState state) {
        if(stateThread != null) {
            stateThread.interrupt();
            stateThread = null;
        }
        String previousState = this.state == null ? "NULL" : this.state.toString();
        log.info("Platooning plugin is changing from " + previousState + " state to " + state.toString() + " state");
        this.state = state;
        stateThread = new Thread(state);
        stateThread.start();
        log.debug("Started stateThread");
    }
    
    private void publishPlatooningInfo() {
        //TODO verify all fields have the correct value
        if (platooningInfoPublisher != null) {
            PlatooningInfo info = platooningInfoPublisher.newMessage();
            if(this.state instanceof StandbyState) {
                info.setState(PlatooningInfo.DISABLED);
            } else if(this.state instanceof LeaderState) {
                info.setState(platoonManager.getTotalPlatooningSize() == 1 ? PlatooningInfo.SEARCHING : PlatooningInfo.LEADING);
            } else if(this.state instanceof LeaderWaitingState) {
                info.setState(PlatooningInfo.CONNECTING_TO_NEW_FOLLOWER);
            } else if(this.state instanceof CandidateFollowerState) {
                info.setState(PlatooningInfo.CONNECTING_TO_NEW_LEADER);
            } else if(this.state instanceof FollowerState) {
                info.setState(PlatooningInfo.FOLLOWING);
            }
            if(!(this.state instanceof StandbyState)) {
                info.setPlatoonId(this.platoonManager.currentPlatoonID);
                info.setSize((byte) this.platoonManager.getTotalPlatooningSize());
                info.setSizeLimit((byte) this.maxPlatoonSize);
                PlatoonMember currentLeader = this.platoonManager.getLeader();
                if(currentLeader == null) {
                    info.setLeaderId(pluginServiceLocator.getMobilityRouter().getHostMobilityId());
                    info.setLeaderDowntrackDistance((float) pluginServiceLocator.getRouteService().getCurrentDowntrackDistance());
                    info.setLeaderCmdSpeed((float) pluginServiceLocator.getManeuverPlanner().getManeuverInputs().getCurrentSpeed());
                    info.setHostPlatoonPosition((byte) 0); // platoon position is indexed as 0 based 
                    
                } else {
                    info.setLeaderId(currentLeader.staticId);
                    info.setLeaderDowntrackDistance((float) currentLeader.vehiclePosition);
                    info.setLeaderCmdSpeed((float) currentLeader.vehicleSpeed);
                    info.setHostPlatoonPosition((byte) platoonManager.getNumberOfVehicleInFront()); // platoon position is indexed as 0 based
                }
                info.setHostCmdSpeed((float) (cmdSpeedSub.getLastMessage() != null ? cmdSpeedSub.getLastMessage().getSpeed() : 0.0));
                info.setDesiredGap((float) commandGenerator.desiredGap_);
            }
            platooningInfoPublisher.publish(info);
        }
    }

    /**
     * Helper function to acquire control of the light bar
     */
    private void takeControlOfLightBar() {
        List<LightBarIndicator> acquired = lightBarManager.requestControl(Arrays.asList(LIGHT_BAR_INDICATOR), this.getVersionInfo().componentName(),
            // Lost control of light call back
            (LightBarIndicator lostIndicator) -> {
                lostControlOfLights.set(true);
                log.info("Lost control of light bar indicator: " + LIGHT_BAR_INDICATOR);
        });
        // Check if the control request was successful. 
        if (acquired.contains(LIGHT_BAR_INDICATOR)) {
            lightBarManager.setIndicator(LIGHT_BAR_INDICATOR, lastAttemptedIndicatorStatus, this.getVersionInfo().componentName());
            lostControlOfLights.set(false);
            log.info("Got control of light bar indicator: " + LIGHT_BAR_INDICATOR);
        }
    }

    /**
     * Attempts to set the platooning controlled light bar indicators to the provided status
     * 
     * @param status the indicator status to set
     */
    protected void setLightBarStatus(IndicatorStatus status) {
        if (lightBarManager == null) {
            return;
        }
        lightBarManager.setIndicator(LIGHT_BAR_INDICATOR, status, this.getVersionInfo().componentName());
        lastAttemptedIndicatorStatus = status;
    }
    
    protected IManeuverInputs getManeuverInputs() {
        return pluginServiceLocator.getManeuverPlanner().getManeuverInputs();
    }
    
    protected double getLastSpeedCmd() {
        if(cmdSpeedSub != null && cmdSpeedSub.getLastMessage() != null) {
            return cmdSpeedSub.getLastMessage().getSpeed();
        }
        return 0.0;
    }
    
}
