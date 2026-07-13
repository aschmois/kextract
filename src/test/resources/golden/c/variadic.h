typedef void (*LogCallback)(int level, const char *message);

void log_values(int count, ...);
int format_message(char *buffer, int capacity, ...);
