import os
import requests
import mercantile
from PIL import Image
import numpy as np
import json
from io import BytesIO

CACHE_DIR = "tile_cache"
os.makedirs(CACHE_DIR, exist_ok=True)

# Fetch a Google tile image and cache it
def fetch_tile_image(x, y, z):
    tile_path = os.path.join(CACHE_DIR, f"tile_{z}_{x}_{y}.png")

    if os.path.exists(tile_path):
        return Image.open(tile_path).convert('RGB')

    url = f"http://mts0.googleapis.com/vt/lyrs=m,traffic&x={x}&y={y}&z={z}&style=15"
    response = requests.get(url)
    response.raise_for_status()

    with open(tile_path, "wb") as f:
        f.write(response.content)

    return Image.open(BytesIO(response.content)).convert('RGB')

# Accurate lat/lon calculation using tile coordinates
def tile_coords_to_latlon(x, y, z, px, py, tile_size):
    n = 2.0 ** z
    lon = (x + px / tile_size) / n * 360.0 - 180.0
    lat_rad = np.arctan(np.sinh(np.pi * (1 - 2 * (y + py / tile_size) / n)))
    lat = np.degrees(lat_rad)
    return lat, lon

# Process the image to extract traffic data based on pixel colors
def extract_traffic_from_image(img, x, y, z):
    img_np = np.array(img)
    traffic_data = []

    traffic_colors = {
        "heavy": ([180, 0, 0], [255, 100, 100]),      # Red shades
        "moderate": ([200, 100, 0], [255, 180, 100]), # Orange shades
        "light": ([0, 100, 0], [100, 255, 100])       # Green shades
    }

    tile_size = img_np.shape[0]

    for py in range(tile_size):
        for px in range(tile_size):
            pixel = img_np[py, px, :3]
            for traffic_type, (lower, upper) in traffic_colors.items():
                if all(lower[i] <= pixel[i] <= upper[i] for i in range(3)):
                    lat, lon = tile_coords_to_latlon(x, y, z, px, py, tile_size)
                    traffic_data.append({
                        "lat": lat,
                        "lon": lon,
                        "traffic": traffic_type
                    })
                    break

    return traffic_data

# Generate traffic JSON for Chisinau city with higher zoom
def generate_city_traffic_json(city_bbox, zoom):
    tiles = mercantile.tiles(city_bbox['west'], city_bbox['south'], city_bbox['east'], city_bbox['north'], zoom)
    all_traffic_data = []

    for tile in tiles:
        try:
            # if tile.x == 19007 and tile.y == 11524 and tile.z == 15:
            img = fetch_tile_image(tile.x, tile.y, tile.z)
            traffic_data = extract_traffic_from_image(img, tile.x, tile.y, tile.z)
            all_traffic_data.extend(traffic_data)
        except Exception as e:
            print(f"Error processing tile ({tile.x}, {tile.y}, {tile.z}): {e}")

    output_file = f"chisinau_traffic_z{zoom}.json"
    with open(output_file, "w") as f:
        json.dump(all_traffic_data, f, indent=2)

    return output_file

# Chisinau city bounding box
chisinau_bbox = {'west': 28.73, 'south': 46.94, 'east': 28.92, 'north': 47.07}
zoom_level = 10

output_path = generate_city_traffic_json(chisinau_bbox, zoom_level)
print(f"Traffic data saved to {output_path}")
