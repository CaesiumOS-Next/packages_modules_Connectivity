/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import android.net.RouteInfo;

/** @hide */
interface IRoutingCoordinator {
   /**
    * Add a route for specific network
    *
    * @param netId the network to add the route to
    * @param route the route to add
    * @throws ServiceSpecificException in case of failure, with an error code indicating the
    *         cause of the failure.
    */
    void addRoute(int netId, in RouteInfo route);

   /**
    * Remove a route for specific network
    *
    * @param netId the network to remove the route from
    * @param route the route to remove
    * @throws ServiceSpecificException in case of failure, with an error code indicating the
    *         cause of the failure.
    */
    void removeRoute(int netId, in RouteInfo route);
}
