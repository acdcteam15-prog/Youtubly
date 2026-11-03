"""Regenerates the Android logo drawables from agent/remoty_logo.py (run: python make_android_logo.py)."""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "agent"))
import remoty_logo as L

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "src", "main", "res", "drawable")


def path(poly):
    pts = " L".join(f"{x:.2f},{y:.2f}" for x, y in poly)
    return f"M{pts} Z"


def paths(size, k, color):
    return "\n".join(f'    <path android:fillColor="{color}" android:pathData="{path(p)}" />' for p in L.shapes_for(size, k))


def vector(size, body):
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{size}dp" android:height="{size}dp" android:viewportWidth="{size}" android:viewportHeight="{size}">\n{body}\n</vector>\n')


def write(name, text):
    with open(os.path.join(RES, name), "w", encoding="utf-8") as f:
        f.write(text)


os.makedirs(RES, exist_ok=True)
# launcher icon: red gradient background + white R inside the 66dp safe zone (also used as the monochrome layer)
write("ic_launcher_background.xml", vector(108, '''    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:startX="0" android:startY="0" android:endX="108" android:endY="108" android:type="linear">
                <item android:color="#FFFF5252" android:offset="0" />
                <item android:color="#FFB71C1C" android:offset="1" />
            </gradient>
        </aapt:attr>
    </path>'''))
write("ic_launcher_foreground.xml", vector(108, paths(108, 1.1, "#FFFFFFFF")))
# status-bar icon: white R only
write("ic_stat_remoty.xml", vector(24, paths(24, 1.5, "#FFFFFFFF")))
# in-app logo: red rounded square + white R (solid red: no gradient parsing needed at runtime)
rr = 'M20,0 h68 a20,20 0 0 1 20,20 v68 a20,20 0 0 1 -20,20 h-68 a20,20 0 0 1 -20,-20 v-68 a20,20 0 0 1 20,-20 z'
write("ic_logo.xml", vector(108, f'    <path android:fillColor="#FFD32F2F" android:pathData="{rr}" />\n' + paths(108, 1.35, "#FFFFFFFF")))
print("logo drawables written")
