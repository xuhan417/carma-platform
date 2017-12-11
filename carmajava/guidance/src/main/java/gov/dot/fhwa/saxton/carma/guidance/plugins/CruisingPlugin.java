/*
 * Copyright (C) 2017 LEIDOS.
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

package gov.dot.fhwa.saxton.carma.guidance.plugins;

import gov.dot.fhwa.saxton.carma.guidance.ManeuverPlanner;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.IManeuver;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.LongitudinalManeuver;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.SlowDown;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.SpeedUp;
import gov.dot.fhwa.saxton.carma.guidance.maneuvers.SteadySpeed;
import gov.dot.fhwa.saxton.carma.guidance.trajectory.Trajectory;
import gov.dot.fhwa.saxton.carma.guidance.util.RouteService;
import gov.dot.fhwa.saxton.carma.guidance.util.SpeedLimit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;

/**
 * Cruising Plugin
 * </p>
 * Implements the basic behavior of commanding the speed limit (as specified by RouteManager)
 * at each route segment.
 */
public class CruisingPlugin extends AbstractPlugin {
    
  protected double maxAccel_;
  protected static final double DISTANCE_EPSILON = 0.0001;

  protected class TrajectorySegment {
    double start;
    double end;
    double startSpeed;
    double endSpeed;
  }

  public CruisingPlugin(PluginServiceLocator psl) {
    super(psl);
    version.setName("Cruising Plugin");
    version.setMajorRevision(0);
    version.setIntermediateRevision(0);
    version.setMinorRevision(1);
  }

  @Override
  public void onInitialize() {
    log.info("Cruisng plugin initializing...");
    maxAccel_ = pluginServiceLocator.getParameterSource().getDouble("~vehicle_acceleration_limit", 2.5);
    log.info("Cruising plugin initialized.");
  }

  @Override
  public void loop() {
    try {
      Thread.sleep(500);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void onResume() {
    setAvailability(true);
  }

  @Override
  public void onSuspend() {
    setAvailability(false);
  }

  @Override
  public void onTerminate() {
    // NO-OP
  }

  protected boolean fpEquals(double a, double b, double epsilon) {
    return Math.abs(a - b) < epsilon;
  }

  protected List<TrajectorySegment> findTrajectoryGaps(Trajectory traj, double trajStartSpeed, double trajEndSpeed) {
    List<LongitudinalManeuver> longitudinalManeuvers = traj.getLongitudinalManeuvers();
    longitudinalManeuvers.sort(new Comparator<IManeuver>() {
      @Override
      public int compare(IManeuver o1, IManeuver o2) {
        return Double.compare(o1.getStartDistance(), o2.getStartDistance());
      }
    });

    List<TrajectorySegment> gaps = new ArrayList<>();

    // Will be the only case to be handled for now, nothing else should be generating longitudinal maneuvers
    if (longitudinalManeuvers.isEmpty()) {
      TrajectorySegment seg = new TrajectorySegment();
      seg.start = traj.getStartLocation();
      seg.end = traj.getEndLocation();
      seg.startSpeed = trajStartSpeed;
      seg.endSpeed = trajEndSpeed;

      gaps.add(seg);

      return gaps;
    }

    // Find the gaps between
    if (!fpEquals(longitudinalManeuvers.get(0).getStartDistance(), traj.getStartLocation(), DISTANCE_EPSILON)) {
      TrajectorySegment seg = new TrajectorySegment();
      seg.start = traj.getStartLocation();
      seg.end = longitudinalManeuvers.get(0).getStartDistance();
      seg.startSpeed = trajStartSpeed;
      seg.endSpeed = longitudinalManeuvers.get(0).getStartSpeed();

      gaps.add(seg);
    }

    LongitudinalManeuver prev = null;
    for (LongitudinalManeuver maneuver : longitudinalManeuvers) {
      if (prev != null) {
        if (!fpEquals(prev.getEndDistance(), maneuver.getStartDistance(), DISTANCE_EPSILON)) {
          TrajectorySegment seg = new TrajectorySegment();
          seg.start = prev.getEndDistance();
          seg.end = maneuver.getStartDistance();
          seg.startSpeed = prev.getTargetSpeed();
          seg.endSpeed = maneuver.getTargetSpeed();
          gaps.add(seg);
        }
      }

      prev = maneuver;
    }

    if (!fpEquals(prev.getEndDistance(), traj.getEndLocation(), DISTANCE_EPSILON)) {
      TrajectorySegment seg = new TrajectorySegment();
      seg.start = prev.getEndDistance();
      seg.end = traj.getEndLocation();
      seg.startSpeed = prev.getTargetSpeed();
      seg.endSpeed = trajEndSpeed;
      gaps.add(seg);
    }

    return gaps;
  }

  /**
   * It tries to plan a maneuver with give startDist, endDist, startSpeed and endSpeed.
   * The return value is the actual endSpeed.
   */
  protected double planManeuvers(Trajectory t, double startDist, double endDist, double startSpeed, double endSpeed) {
    ManeuverPlanner planner = pluginServiceLocator.getManeuverPlanner();
    log.info(String.format("Trying to plan maneuver {start=%.2f,end=%.2f,startSpeed=%.2f,endSpeed=%.2f}", startDist, endDist, startSpeed, endSpeed));
    double maneuverEnd = startDist;
    double adjustedEndSpeed = startSpeed;
    if(startSpeed < endSpeed) {
      // Generate a speed-up maneuver
      SpeedUp speedUp = new SpeedUp();
      speedUp.setSpeeds(startSpeed, endSpeed);
      speedUp.setMaxAccel(maxAccel_);
      if(planner.canPlan(speedUp, startDist, endDist)) {
        planner.planManeuver(speedUp, startDist);
        if(speedUp.getEndDistance() > endDist) {
            maneuverEnd = endDist;
            adjustedEndSpeed = planner.planManeuver(speedUp, startDist, endDist);
        } else {
            maneuverEnd = speedUp.getEndDistance();
            adjustedEndSpeed = endSpeed;
        }
        t.addManeuver(speedUp);
        log.info(String.format("Planned SPEED-UP maneuver from [%.2f, %.2f) m/s over [%.2f, %.2f) m", startSpeed, endSpeed, startDist, endDist));
      }
    } else if(startSpeed > endSpeed) {
      // Generate a slow-down maneuver
      SlowDown slowDown = new SlowDown();
      slowDown.setSpeeds(startSpeed, endSpeed);
      slowDown.setMaxAccel(maxAccel_);
      if(planner.canPlan(slowDown, startDist, endDist)) {
        planner.planManeuver(slowDown, startDist);
        if(slowDown.getEndDistance() > endDist) {
            maneuverEnd = endDist;
            adjustedEndSpeed = planner.planManeuver(slowDown, startDist, endDist);
        } else {
            maneuverEnd = slowDown.getEndDistance();
            adjustedEndSpeed = endSpeed;
        }
        t.addManeuver(slowDown);
        log.info(String.format("Planned SLOW-DOWN maneuver from [%.2f, %.2f) m/s over [%.2f, %.2f) m", startSpeed, endSpeed, startDist, endDist));
      }
    }
    
    // Insert a steady speed maneuver to fill whatever's left
    if(maneuverEnd < endDist) {
        SteadySpeed steady = new SteadySpeed();
        steady.setSpeeds(adjustedEndSpeed, adjustedEndSpeed);
        steady.setMaxAccel(maxAccel_);
        planner.planManeuver(steady, maneuverEnd);
        steady.overrideEndDistance(endDist);
        t.addManeuver(steady);
        log.info(String.format("Planned STEADY-SPEED maneuver at %.2f m/s over [%.2f, %.2f) m", adjustedEndSpeed, adjustedEndSpeed, endDist));
    }
    
    return adjustedEndSpeed;
  }

  // It can only plan trajectory without preplanned longitudinal maneuvers.
  @Override
  public void planTrajectory(Trajectory traj, double expectedEntrySpeed) {
    // Use RouteService to find all necessary speed limits
    RouteService routeService = pluginServiceLocator.getRouteService();
    double endLocation = traj.getComplexManeuver() == null ? traj.getEndLocation() : traj.getComplexManeuver().getStartDistance();
    SortedSet<SpeedLimit> trajLimits = routeService.getSpeedLimitsInRange(traj.getStartLocation(), endLocation);
    trajLimits.add(routeService.getSpeedLimitAtLocation(endLocation));
    
    // Plan trajectory to follow all speed limits
    double newManeuverStartSpeed = expectedEntrySpeed;
    double newManeuverStartLocation = traj.getStartLocation();
    for(SpeedLimit limit : trajLimits) {
        newManeuverStartSpeed = planManeuvers(traj, newManeuverStartLocation, limit.getLocation(), newManeuverStartSpeed, limit.getLimit());
        newManeuverStartLocation = limit.getLocation();
    }
  }
}
