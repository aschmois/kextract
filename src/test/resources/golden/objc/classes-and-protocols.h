@class NSString;

@interface NSObject
@end

@protocol Greeting
- (NSString *)greet;
@optional
- (void)reset;
@end

@interface Person : NSObject <Greeting>
@property (nonatomic, copy) NSString *name;
- (NSString *)greetWithPrefix:(NSString *)prefix;
@end
