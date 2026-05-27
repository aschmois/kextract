@interface Counter : NSObject
@property (readonly) int count;
- (void)increment;
- (void)incrementBy:(int)amount;
+ (Counter *)counterWithStart:(int)start;
@end
