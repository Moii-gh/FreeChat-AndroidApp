import re

file_path = r'c:\Users\user\Desktop\chatapp\app\src\main\res\layout\activity_main.xml'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace opening of inputCapsule
blur_start = '''            <eightbitlab.com.blurview.BlurView
                android:id="@+id/blurView"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="0dp"
                android:background="@drawable/input_capsule_bg"
                app:blurOverlayColor="#00000000">

            <LinearLayout
                android:id="@+id/inputCapsule"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingHorizontal="4dp"
                android:paddingVertical="4dp">'''

content = re.sub(
    r'<LinearLayout\s+android:id="@+id/inputCapsule"[\s\S]*?android:paddingVertical="4dp">', 
    blur_start, 
    content
)

content = content.replace(
    '            </LinearLayout>\n            </FrameLayout>', 
    '            </LinearLayout>\n            </eightbitlab.com.blurview.BlurView>\n            </FrameLayout>'
)

content = content.replace(
    '            </LinearLayout>\r\n            </FrameLayout>', 
    '            </LinearLayout>\r\n            </eightbitlab.com.blurview.BlurView>\r\n            </FrameLayout>'
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
