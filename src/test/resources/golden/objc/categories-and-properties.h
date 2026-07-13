@class NSString;

@interface NSObject
@end

@interface Widget : NSObject
@property (readonly) int identifier;
@property (nonatomic, copy) NSString *title;
- (void)refresh;
@end

@interface Widget (Lifecycle)
- (void)reset;
+ (Widget *)widgetWithIdentifier:(int)identifier;
@end
