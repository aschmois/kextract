struct Point {
    int x;
    int y;
};

union Number {
    int integer;
    float decimal;
};

enum Color {
    Red,
    Green,
    Blue
};

int add(int left, int right);
void log_message(const char *message);
