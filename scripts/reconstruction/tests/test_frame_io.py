import numpy as np

from frame_io import (
    Intrinsics,
    PointCloudArrays,
    apply_transform,
    build_point_cloud_from_frame,
    merge_session_point_clouds,
    project_rgbd_to_points,
    voxel_down_sample,
)


def test_project_rgbd_round_trip_recovers_depth():
    """A flat depth plane at a known distance should project to points all
    at that same Z (the pinhole model's z output IS the depth value)."""
    height, width = 32, 32
    depth_meters_value = 2.5
    depth_img = np.full((height, width), depth_meters_value, dtype=np.float32)
    color_img = np.zeros((height, width, 3), dtype=np.uint8)
    color_img[..., 0] = 200  # solid red, so color-passthrough is checkable too

    intr = Intrinsics(fx=50.0, fy=50.0, cx=width / 2, cy=height / 2, width=width, height=height)
    cloud = project_rgbd_to_points(color_img, depth_img, intr)

    assert cloud.points.shape[0] == height * width
    assert np.allclose(cloud.points[:, 2], depth_meters_value, atol=1e-6)
    assert np.allclose(cloud.colors[:, 0], 200 / 255.0)
    assert np.allclose(cloud.colors[:, 1], 0.0)


def test_project_rgbd_drops_zero_and_far_depth():
    depth_img = np.array([[0.0, 0.5], [9000.0, 2.0]], dtype=np.float32)  # 0m (invalid), 0.5m, 9000m (far), 2m
    color_img = np.zeros((2, 2, 3), dtype=np.uint8)
    intr = Intrinsics(fx=10.0, fy=10.0, cx=1.0, cy=1.0, width=2, height=2)

    cloud = project_rgbd_to_points(color_img, depth_img, intr)
    # Only the two valid, in-range depths (0.5m, 2m) should survive.
    assert cloud.points.shape[0] == 2
    assert set(np.round(cloud.points[:, 2], 3)) == {0.5, 2.0}


def test_build_point_cloud_from_frame_applies_pose_on_top_of_projection():
    """Verify by comparing against manually transforming project_rgbd_to_points'
    output."""
    height, width = 8, 8
    depth_img = np.full((height, width), 1.0, dtype=np.float32)  # 1m everywhere
    color_img = np.full((height, width, 3), 128, dtype=np.uint8)
    intr = Intrinsics(fx=20.0, fy=20.0, cx=4.0, cy=4.0, width=width, height=height)

    camera_transform = np.eye(4)
    camera_transform[:3, 3] = [10.0, 20.0, 30.0]

    result = build_point_cloud_from_frame(color_img, depth_img, camera_transform, intr)

    expected_camera_space = project_rgbd_to_points(color_img, depth_img, intr)
    expected_world = apply_transform(expected_camera_space.points, camera_transform)
    assert np.allclose(result.points, expected_world)


def test_build_point_cloud_from_frame_resizes_color_to_depth_resolution():
    """ARKit's depth is commonly lower-res than the video feed -- color
    should be downsampled to match, not the other way around."""
    depth_img = np.full((4, 4), 1.0, dtype=np.float32)
    color_img = np.full((16, 16, 3), 100, dtype=np.uint8)  # 4x higher res than depth
    intr = Intrinsics(fx=5.0, fy=5.0, cx=2.0, cy=2.0, width=4, height=4)

    result = build_point_cloud_from_frame(color_img, depth_img, np.eye(4), intr)
    assert result.points.shape[0] == 16  # one point per depth pixel, not per color pixel


def test_voxel_down_sample_merges_nearby_points():
    points = np.array([[0.0, 0.0, 0.0], [0.001, 0.001, 0.001], [5.0, 5.0, 5.0]])
    colors = np.array([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]])
    down = voxel_down_sample(PointCloudArrays(points=points, colors=colors), voxel_size=0.05)
    # The two near-identical points collapse into one voxel; the far point stays separate.
    assert down.points.shape[0] == 2


def test_merge_session_point_clouds_concatenates_and_downsamples():
    a = PointCloudArrays(points=np.array([[0.0, 0.0, 0.0]]), colors=np.array([[1.0, 0.0, 0.0]]))
    b = PointCloudArrays(points=np.array([[10.0, 10.0, 10.0]]), colors=np.array([[0.0, 1.0, 0.0]]))
    merged = merge_session_point_clouds([a, b], voxel_size=0.05)
    assert merged.points.shape[0] == 2
