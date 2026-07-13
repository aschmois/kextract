struct Window {
    int width;
    int height;
};

const int WindowStyle = 1;
int CreateWindow(int width, int height);
int GetWindowText(int handle, char *buffer, int length);
