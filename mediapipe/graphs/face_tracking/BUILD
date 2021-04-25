# Copyright 2019 The MediaPipe Authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

licenses(["notice"])

package(default_visibility = ["//visibility:public"])

cc_library(
    name = "desktop_live_calculators",
    deps = [
        "//mediapipe/calculators/core:packet_resampler_calculator",
        "//mediapipe/modules/face_detection:face_detection_front_cpu",
        "//mediapipe/graphs/tracking/subgraphs:object_tracking_cpu",
        "//mediapipe/graphs/tracking/subgraphs:renderer_cpu",
    ],
)

cc_library(
    name = "blur_live_calculators",
    deps = [
        "//mediapipe/calculators/core:flow_limiter_calculator",
        "//mediapipe/calculators/image:image_properties_calculator",
        "//mediapipe/calculators/util:detections_to_rects_calculator",
        "//mediapipe/calculators/util:detection_to_landmarks_calculator",
        "//mediapipe/modules/face_detection:face_detection_front_cpu",
        "//mediapipe/calculators/core:begin_loop_calculator",
        "//mediapipe/calculators/core:end_loop_calculator",
        "//mediapipe/calculators/core:split_normalized_landmark_list_calculator",
        "//mediapipe/calculators/util:rect_to_render_data_calculator",
        "//mediapipe/calculators/util:landmarks_smoothing_calculator",
        "//mediapipe/calculators/util:annotation_overlay_calculator",
        "//mediapipe/modules/face_landmark:face_landmark_front_cpu",
        "//mediapipe/calculators/util:landmarks_to_render_data_calculator",
        "//mediapipe/graphs/face_mesh/calculators:face_landmarks_to_render_data_calculator"
    ],
)

cc_library(
    name = "desktop_live_gpu_calculators",
    deps = [
        "//mediapipe/calculators/core:flow_limiter_calculator",
        "//mediapipe/calculators/util:annotation_overlay_calculator",
        "//mediapipe/calculators/util:detections_to_render_data_calculator",
        "//mediapipe/modules/face_detection:face_detection_front_gpu",
    ],
)
