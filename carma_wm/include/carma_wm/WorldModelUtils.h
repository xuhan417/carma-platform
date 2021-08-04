#pragma once

/*
 * Copyright (C) 2021 LEIDOS.
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

#include <exception>
#include <lanelet2_core/LaneletMap.h>
#include <lanelet2_core/primitives/Area.h>
#include <lanelet2_core/primitives/Point.h>
#include <lanelet2_core/primitives/Lanelet.h>
#include <lanelet2_core/primitives/Polygon.h>
#include <lanelet2_core/geometry/Polygon.h>
#include <lanelet2_core/geometry/LineString.h>
#include <lanelet2_core/utility/Optional.h>
#include "TrackPos.h"
#include <boost/geometry.hpp>
#include <boost/geometry/geometries/polygon.hpp>
#include <ros/ros.h>
#include <carma_wm/Geometry.h>

namespace carma_wm
{
namespace query
{
/**
 * \brief carma_wm::query namespace contains implementations for query functions (input and output read or write-able) for stand-alone lanelet map without rest of the CARMAWorldModel features.
 *        Currently mainly WMBroadcaster (carma_wm_ctrl) is using this to manipulate its own map without creating instance of carma_wm
 */

/**
 * \brief Gets the underlying lanelet, given the cartesian point on the map
 *
 * \param semantic_map  Lanelet Map Ptr
 * \param point         Cartesian point to check the corressponding lanelet
 * \param n             Number of lanelets to return. Default is 10. As there could be many lanelets overlapping.
 * \throw std::invalid_argument if the map is not set, contains no lanelets
 *
 * \return vector of underlying lanelet, empty vector if it is not part of any lanelet
 */
std::vector<lanelet::ConstLanelet> getLaneletsFromPoint(const lanelet::LaneletMapConstPtr& semantic_map, const lanelet::BasicPoint2d& point,
                                                          const unsigned int n = 10);

/**
 * \brief (non-const version) Gets the underlying lanelet, given the cartesian point on the map 
 *
 * \param semantic_map  Lanelet Map Ptr
 * \param point         Cartesian point to check the corressponding lanelet
 * \param n             Number of lanelets to return. Default is 10. As there could be many lanelets overlapping.
 * \throw std::invalid_argument if the map is not set, contains no lanelets
 *
 * \return vector of underlying lanelet, empty vector if it is not part of any lanelet
 */
std::vector<lanelet::Lanelet> getLaneletsFromPoint(const lanelet::LaneletMapPtr& semantic_map, const lanelet::BasicPoint2d& point,
                                                                    const unsigned int n = 10);
/**
 * \brief Given the cartesian point on the map, tries to get the opposite direction lanelet on the left
 *        This function is intended to find "adjacentLeft lanelets" that doesn't share points between lanelets
 *        where adjacentLeft of lanelet library fails
 *
 * \param semantic_map  Lanelet Map Ptr
 * \param point         Cartesian point to check the corressponding lanelet
 * \param n             Number of lanelets to return. Default is 10. As there could be many lanelets overlapping.
 * 
   * \throw std::invalid_argument if the map is not set, contains no lanelets, or if adjacent lanelet is not opposite direction
 * NOTE:  Only to be used on 2 lane, opposite direction road. Number of points in all linestrings are assumed to be roughly the same.
 *        The point is assumed to be on roughly similar shape of overlapping lanelets if any
 *        Enhancement issue for protection against checking if the laneis opposite direction here:
 *        https://github.com/usdot-fhwa-stol/carma-platform/issues/1381
 * \return vector of underlying lanelet, empty vector if it is not part of any lanelet
 */
std::vector<lanelet::ConstLanelet> nonConnectedAdjacentLeft(const lanelet::LaneletMapConstPtr& semantic_map, const lanelet::BasicPoint2d& input_point,
                                                          const unsigned int n = 10);

/**
 * \brief (non-const version) Given the cartesian point on the map, tries to get the opposite direction lanelet on the left
 *        This function is intended to find "adjacentLeft lanelets" that doesn't share points between lanelets
 *        where adjacentLeft of lanelet library fails
 *
 * \param semantic_map  Lanelet Map Ptr
 * \param point         Cartesian point to check the corressponding lanelet
 * \param n             Number of lanelets to return. Default is 10. As there could be many lanelets overlapping.
 * 
   * \throw std::invalid_argument if the map is not set, contains no lanelets, or if adjacent lanelet is not opposite direction
 * NOTE:  Only to be used on 2 lane, opposite direction road. Number of points in all linestrings are assumed to be roughly the same.
 *        The point is assumed to be on roughly similar shape of overlapping lanelets if any
 *        Enhancement issue for protection against checking if the laneis opposite direction here:
 *        https://github.com/usdot-fhwa-stol/carma-platform/issues/1381
 * \return vector of underlying lanelet, empty vector if it is not part of any lanelet
 */
std::vector<lanelet::Lanelet> nonConnectedAdjacentLeft(const lanelet::LaneletMapPtr& semantic_map, const lanelet::BasicPoint2d& input_point,
                                                          const unsigned int n = 10);

} // namespace query

namespace utils
{

/*! \brief Get 32bit id by concatenating 16bit id with 8bit signal_group_id
 *  \param intersection_id 16bit id which will be shifted left 8bits
 *  \param signal_group_id 8bit signal_group_id
 *  \return 32bit id where last 24bit is combined id of inputs
 */
uint32_t get32BitId(uint16_t intersection_id, uint8_t signal_group_id);

}  // namespace utils

}  // namespace carma_wm