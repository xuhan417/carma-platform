#include "message_to_external_object_convertor.h"
#include <carma_v2x_msgs/psm.hpp>
#include <carma_perception_msgs/external_object.hpp>

namespace object
{
    // TODO there is no purpose to this inheritance. Each message will require potentially differing parameters which would need to be set based on the message type which invalidates most of the poly morphism benefits. 
    class PsmToExternalObject : public MessageToExternalObjectConvertor<carma_v2x_msgs::msg::PSM>
    {

        std::string frame_id_;
        PsmToExternalObject(std::string frame_id) {
            frame_id_ = frame_id;
        }

        std::vector<geometry_msgs::msg::Pose> sample_2d_path_from_radius(
            const geometry_msgs::msg::Pose& pose, double velocity, 
            double radius_of_curvature, double period, double step_size) 
        {
            std::vector<geometry_msgs::msg::Pose> output;
            output.reserve( (period / step_size) + 1);

            tf2::Vector3 pose_in_map_translation(pose.position.x, pose.position.y, pose.position.z);
            tf2::Quaternion pose_in_map_quat(pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w);
            tf2::Transform pose_in_map(pose_in_map_translation, pose_in_map_quat);


            // The radius of curvature originates from the frame of the provided pose
            // So the turning center is at (0, r)
            double center_x_in_pose = 0;
            double center_y_in_pose = radius_of_curvature;

            double total_dt = 0;
            
            while (total_dt < period) {
                
                // Compute the 2d position and orientation in the Pose frame
                total_dt += step_size;
                double delta_arc_length = velocity * total_dt; // Assumes perfect point motion along curve

                double turning_angle = delta_arc_length / radius_of_curvature;
                double dx_from_center = radius_of_curvature * sin(turning_angle);
                double dy_from_center = radius_of_curvature * cos(turning_angle);

                double x = center_x_in_pose + dx_from_center;
                double y = center_y_in_pose + dy_from_center;

                tf2::Vector3 position(x,y, 0);

                tf2::Quaternion quat;
                quat.setRPY(0,0, turning_angle);
                sample_pose.orientation.x = quat.x();
                sample_pose.orientation.y = quat.y();
                sample_pose.orientation.z = quat.z();
                sample_pose.orientation.w = quat.w();

                // Convert the position and orientation in the pose frame to the map frame
                tf2::Transform pose_to_sample(position, quat);
                tf2::Transform map_to_sample = pose_in_map * pose_to_sample;

                geometry_msgs::msg::Pose sample_pose;

                sample_pose.position.x = map_to_sample.translation().x();
                sample_pose.position.y = map_to_sample.translation().y();
                sample_pose.position.z = pose.orientation.z; // Reuse the z position from the initial pose

                sample_pose.orientation.x = map_to_sample.rotation().x();
                sample_pose.orientation.y = map_to_sample.rotation().y();
                sample_pose.orientation.z = map_to_sample.rotation().z();
                sample_pose.orientation.w = map_to_sample.rotation().w();

                output.emplace_back(sample_pose);

            }

            return output;
        }

        std::vector<geometry_msgs::msg::Pose> sample_2d_linear_motion(
            const geometry_msgs::msg::Pose& pose, double velocity, double period, double step_size) 
        {
            std::vector<geometry_msgs::msg::Pose> output;
            output.reserve( (period / step_size) + 1);

            tf2::Vector3 pose_in_map_translation(pose.position.x, pose.position.y, pose.position.z);
            tf2::Quaternion pose_in_map_quat(pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w);
            tf2::Transform pose_in_map(pose_in_map_translation, pose_in_map_quat);

            double total_dt = 0;
            
            while (total_dt < period) {
                
                // Compute the 2d position and orientation in the Pose frame
                total_dt += step_size;
                double dx_from_start = velocity * total_dt; // Assuming linear motion in pose frame

                double x = pose.position.x + dx_from_start;

                tf2::Vector3 position(x,0, 0);

                // Convert the position and orientation in the pose frame to the map frame
                tf2::Transform pose_to_sample(position, tf2::Quaternion::getIdentity());
                tf2::Transform map_to_sample = pose_in_map * pose_to_sample;

                geometry_msgs::msg::Pose sample_pose;

                sample_pose.position.x = map_to_sample.translation().x();
                sample_pose.position.y = map_to_sample.translation().y();
                sample_pose.position.z = map_to_sample.translation().z();

                sample_pose.orientation.x = map_to_sample.rotation().x();
                sample_pose.orientation.y = map_to_sample.rotation().y();
                sample_pose.orientation.z = map_to_sample.rotation().z();
                sample_pose.orientation.w = map_to_sample.rotation().w();

                output.emplace_back(sample_pose);

            }

            return output;
        }
        
        void convert(const carma_v2x_msgs::msg::PSM &in_msg, carma_perception_msgs::msg::ExternalObject &out_msg)
        {            
            out_msg.dynamic_obj = true; // If a PSM is sent then the object is dynamic since its a living thing
            out_msg.presence_vector |= carma_perception_msgs::msg::ExternalObject::DYNAMIC_OBJ_PRESENCE;

            // Generate a unique object id from the psm id
            out_msg.id = 0
            for (int i = in_msg.id.id.size() - 1; i >= 0; i--) { // using signed iterator to handle empty case
                // each byte of the psm id gets placed in one byte of the object id.
                // This should result in very large numbers which will be unlikely to conflict with standard detections
                out_msg.id |= in_msg.id.id[i] << (8*i); 
            }
            out_msg.presence_vector |= carma_perception_msgs::msg::ExternalObject::ID_PRESENCE_VECTOR;

            // Additionally, store the id in the bsm_id field
            out_msg.bsm_id = in_msg.id.id;
            out_msg.presence_vector |= carma_perception_msgs::msg::ExternalObject::BSM_ID_PRESENCE_VECTOR;

            // Compute the pose
            out_msg.pose = pose_from_gnss(
                map_projector_, 
                ned_in_map_rotation_,
                { in_msg.position.latitude, in_msg.position.longitude, in_msg.position.elevation }, 
                in_msg.heading.heading
            );
            out_msg.presence_vector |= carma_perception_msgs::msg::ExternalObject::POSE_PRESENCE_VECTOR;

            // Compute the timestamp

            out_msg.header.stamp = builtin_interfaces::msg::Time( get_psm_timestamp(in_msg) );
            out_msg.header.frame_id = frame_id_;

            // Set the type
            if (in_msg.basic_type.type == carma_v2x_msgs::PersonalDeviceUserType::A_PEDESTRIAN
                || in_msg.basic_type.type == carma_v2x_msgs::PersonalDeviceUserType::A_PUBLIC_SAFETY_WORKER
                || in_msg.basic_type.type == carma_v2x_msgs::PersonalDeviceUserType::AN_ANIMAL) // Treat animals like people since we have no internal class for that
            {
                out_msg.object_type = carma_perception_msgs::msg::ExternalObject::PEDESTRIAN;

                // Default pedestrian size
                // Assume a 
                // ExternalObject dimensions are half actual size
                // Here we assume 1.0, 1.0, 2.0
                out_msg.size.x = 0.5;
                out_msg.size.y = 0.5;
                out_msg.size.z = 1.0;

            } else if (in_msg.basic_type.type == carma_v2x_msgs::PersonalDeviceUserType::A_PEDALCYCLIST) {
                
                out_msg.object_type = carma_perception_msgs::msg::ExternalObject::MOTORCYCLE; // Currently external object cannot represent bicycles, but motor cycle seems like the next best choice
            
                // Default bicycle size
                out_msg.size.x = 1.0;
                out_msg.size.y = 0.5;
                out_msg.size.z = 1.0;

            } else {
                
                out_msg.object_type = carma_perception_msgs::msg::ExternalObject::UNKNOWN;
            
                // Default pedestrian size
                out_msg.size.x = 0.5;
                out_msg.size.y = 0.5;
                out_msg.size.z = 1.0;

            }
            out_msg.presence_vector |= carma_perception_msgs::msg::ExternalObject::SIZE_PRESENCE_VECTOR;

            // Set the velocity
            out_msg.velocity.twist.linear.x = in_msg.velocity.velocity;
            out_msg.presence_vector |= carma_perception_msgs::msg::ExternalObject::VELOCITY_PRESENCE_VECTOR;
            // NOTE: The velocity covariance is not provided in the PSM. In order to compute it you need at least two PSM messages
            //       Tracking and associating PSM messages would be an increase in complexity for this conversion which is not warranted without an existing
            //       use case for the velocity covariance. If a use case is presented for it, such an addition can be made at that time. 

            // Compute the position covariance
            // There is no easy way to convert this to a oriented 3d covariance since the orientation of the map frame is needed. 
            // For now we will use the largest value and assume it applies to all three directions. This should be a pessimistic estimate which is safer in this case.
            double position_std = std::max(in_msg.accuracy.semi_major, in_msg.accuracy.semi_minor);

            double position_variance = position_std * position_std; // variance is standard deviation squared

            double yaw_std = in_msg.accuracy.orientation;

            double yaw_variance = in_msg.accuracy.orientation * in_msg.accuracy.orientation; // variance is standard deviation squared

            double position_confidence = 0.1; // Default will be 10% confidence. If the position accuracy is available then this value will be updated

            // A standard deviation which is larger than the acceptable value to give 
            // 95% confidence interval on fitting the pedestrian within one 3.7m lane
            constexpr double MAX_POSITION_STD = 1.85;

            if ((in_msg.accuracy.presence_vector | carma_v2x_msgs::msg::PositionalAccuracy::ACCURACY_AVAILABLE)
                && ((in_msg.accuracy.presence_vector | carma_v2x_msgs::msg::PositionalAccuracy::ACCURACY_ORIENTATION_AVAILABLE)) {
                // Both accuracies available
                // Fill out the diagonal
                out_msg.pose.covariance[0] = position_variance;
                out_msg.pose.covariance[7] = position_variance;
                out_msg.pose.covariance[14] = 0;
                out_msg.pose.covariance[21] = 0;
                out_msg.pose.covariance[28] = 0;
                out_msg.pose.covariance[35] = yaw_variance;

                // NOTE: ExternalObject.msg does not clearly define what is meant by position confidence
                //       Here we are providing a linear scale based on the positional accuracy where 0 confidence would denote 
                //       A standard deviation which is larger than the acceptable value to give 
                //       95% confidence interval on fitting the pedestrian within one 3.7m lane
                // Set the confidence
                // Without a way of getting the velocity confidence from the PSM we will use the position confidence for both
                out_msg.confidence = 1.0 -std::min(1.0, fabs(position_std / MAX_POSITION_STD));
                out_msg.presence_vector |= carma_perception_msgs::msg::ExternalObject::CONFIDENCE_PRESENCE_VECTOR;

            } else if (in_msg.accuracy.presence_vector | carma_v2x_msgs::msg::PositionalAccuracy::ACCURACY_AVAILABLE) {
                // Position accuracy available

                out_msg.pose.covariance[0] = position_variance;
                out_msg.pose.covariance[7] = position_variance;
                out_msg.pose.covariance[14] = 0;

                // Same calculation as shown in above condition. See that for description
                out_msg.confidence = 1.0 -std::min(1.0, fabs(position_std / MAX_POSITION_STD));
                out_msg.presence_vector |= carma_perception_msgs::msg::ExternalObject::CONFIDENCE_PRESENCE_VECTOR;


            } else if (in_msg.accuracy.presence_vector | carma_v2x_msgs::msg::PositionalAccuracy::ACCURACY_ORIENTATION_AVAILABLE)
                // Orientation accuracy available

                out_msg.pose.covariance[21] = 0;
                out_msg.pose.covariance[28] = 0;
                out_msg.pose.covariance[35] = yaw_variance;
            } else {
                // No accuracies available

            }
            
            // Compute predictions 
            // For prediction, if the prediction is available we will sample it
            // If not then assume linear motion

            double period = 2.0; // TODO make parameter the same as the existing prediction period
            double step_size = 0.1;// TODO make parameter the same as current prediction step size
            std::vector<geometry_msgs::msg::Pose> predicted_poses;

            if (in_msg.presence_vector | carma_v2x_msgs::msg::PSM::HAS_PATH_PREDICTION) {

                // Based on the vehicle frame used in j2735 positive should be to the right and negative to the left
                predicted_poses = sample_2d_path_from_radius(out_msg.pose.pose, out_msg.velocity.twist.linear.x, -in_msg.path_prediction.radius_of_curvature, period, step_size);

            } else {
                predicted_poses = sample_2d_linear_motion(out_msg.pose.pose, out_msg.velocity.twist.linear.x, period, step_size);
            }

            out_msg.predictions = predicted_poses_to_predicted_state(
                predicted_poses, rclcpp::Time(out_msg.header.stamp), rclcpp::Duration(step_size * 1e9),
                out_msg.confidence, out_msg.confidence
            );
            out_msg.presence_vector |= carma_perception_msgs::msg::ExternalObject::PREDICTION_PRESENCE_VECTOR;

            
        }

        std::vector<carma_perception_msgs::PredictedState> predicted_poses_to_predicted_state(
            const std::vector<geometry_msgs::msg::Pose>& poses, double constant_velocity, const rclcpp::Time& start_time, const rclcpp::Duration& step_size, const std::string& frame, 
            double initial_pose_confidence, double initial_vel_confidence) 
        {
            std::vector<carma_perception_msgs::PredictedState> output;
            output.reserve(poses.size());

            rclcpp::Time time(start_time);

            for (auto p : poses) {
                time += step_size;s
                carma_perception_msgs::PredictedState pred_state;
                pred_state.header.stamp = time;
                pred_state.frame_id = frame;

                pred_state.predicted_position = p;
                pred_state.predicted_position_confidence = 0.9 * initial_pose_confidence; // Reduce confidence by 10 % per timestep
                initial_pose_confidence = pred_state.predicted_position_confidence;

                pred_state.predicted_velocity.twist.linear.x = constant_velocity;
                pred_state.predicted_velocity_confidence = 0.9 * predicted_velocity_confidence; // Reduce confidence by 10 % per timestep
                predicted_velocity_confidence = pred_state.predicted_velocity_confidence;

                output.push_back(pred_state);

            }
            
        }

        rclcpp::Time get_psm_timestamp(const carma_v2x_msgs::msg::PSM &in_msg) {
            
            boost::posix_time::ptime utc_time_of_current_psm; 

            // Get the utc epoch start time
            static const boost::posix_time::ptime inception_boost( boost::posix_time::time_from_string("1970-01-01 00:00:00.000") ); 

            // Determine if the utc time of the path history can be used instead of the sec_mark
            // The sec_mark is susceptible to large error on minute transitions due to missing "minute of the year" field
            // If the second mark in the path history is identical and the full utc time is provided with ms resolution 
            // then it can be assumed the initial_position is the same as the PSM data and the utc_time can be used instead of sec_mark
            if ((in_msg.presence_vector & carma_v2x_msgs::msg::PSM::HAS_PATH_HISTORY) 
                && (in_msg.path_history.presence_vector & carma_v2x_msgs::msg::PathHistory::HAS_INITIAL_POSITION)
                && (in_msg.path_history.initial_position.presence_vector & carma_v2x_msgs::msg::FullPositionVector::HAS_UTC_TIME)
                && (in_msg.path_history.initial_position.utc_time.presence_vector 
                    & carma_v2x_msgs::msg::FullPositionVector::YEAR
                    & carma_v2x_msgs::msg::FullPositionVector::MONTH
                    & carma_v2x_msgs::msg::FullPositionVector::DAY
                    & carma_v2x_msgs::msg::FullPositionVector::HOUR
                    & carma_v2x_msgs::msg::FullPositionVector::MINUTE 
                    & carma_v2x_msgs::msg::FullPositionVector::SECOND)
                && in_msg.sec_mark.millisecond == in_msg.path_history.initial_position.utc_time.second)
            {
                RCLCPP_DEBUG_STREAM(get_logger(), "Using UTC time of path history to determine PSM timestamp. Assumed valid since UTC is fully specified and sec_mark == utc_time.seconds in this message.");

                boost::posix_time::time_duration time_of_day = hours(in_msg.path_history.initial_position.utc_time.hour) 
                    + minutes(in_msg.path_history.initial_position.utc_time.minute) 
                    + milliseconds(in_msg.path_history.initial_position.utc_time.second);
                
                boost::gregorian::date utc_day(in_msg.path_history.initial_position.utc_time.year, in_msg.path_history.initial_position.utc_time.month, in_msg.path_history.initial_position.utc_time.day);

                utc_time_of_current_psm = boost::posix_time::ptime(utc_day) + time_of_day;
                
            } else { // If the utc time of the path history cannot be used to account for minute change over, then we have to default to the sec mark
                
                RCLCPP_WARN_STREAM_THROTTLE(get_logger(), get_clock(), rclcpp::Duration(5, 0), 
                    "PSM PathHistory utc timstamp does not match sec_mark. Unable to determine the minute of the year used for PSM data. Assuming local clock is exactly synched. This is NOT ADVISED.");

                // Get the current ROS time
                auto current_time = get_clock()->now();
                
                // Convert the ros time to a boost duration
                boost::posix_time::time_duration duration_since_inception( lanelet::time::durationFromSec(current_time.seconds()) );
                
                // Get the current ROS time in UTC
                auto curr_time_boost = inception_boost + duration_since_inception;

                // Get duration of current day
                auto duration_in_day_till_current_time = curr_time_boost.time_of_day();

                // Extract hours and minutes
                long hour_count_in_day = duration_in_day_till_current_time.hours()
                long minute_count_in_hour = duration_in_day_till_current_time.minutes()

                // Get the duration of the minute in the day
                auto start_of_minute_in_day = hours(hour_count_in_day) + minutes(minute_count_in_hour)
                
                // Get the start of the day in ROS time
                boost::posix_time::ptime start_of_day(curr_time_boost.date());

                // Ge the start of the current minute in ROS time
                boost::posix_time::ptime utc_start_of_current_minute = start_of_day + start_of_minute_in_day;

                // Compute the UTC PSM stamp from the sec_mark using ROS time as the clock
                boost::posix_time::time_duration s_in_cur_minute = milliseconds(in_msg.sec_mark.millisecond);

                utc_time_of_current_psm = utc_start_of_current_minute + s_in_cur_minute;

            }

            boost::posix_time::time_duration nsec_since_epoch = utc_time_of_current_psm - inception_boost;

            if (nsec_since_epoch.is_special()) {
                RCLCPP_ERROR_STREAM(get_logger(), "Computed psm nsec_since_epoch is special (computation failed). Value effectively undefined.")
            }

            return rclcpp::Time(nsec_since_epoch.total_nanoseconds());
        }
    };

}